# 등록 전용 이미지로 인스턴스 미리 등록하기

이 문서는 mdt-manager를 compose로 띄울 때 정해 둔 MDT 인스턴스가 자동으로 등록되도록, 모델을 넣은 **등록 전용 이미지**(이하 seed 이미지)를 만들고 쓰는 절차를 설명한다.
배포 서버에는 모델 디렉터리가 없어도 되며, compose 파일과 이미지만 있으면 된다.

예로 드는 인스턴스는 `GShinSung/Lifter` 하나다. 이미지 빌드, compose 실행 같은 mdt-manager 이미지 자체의 절차는 [README.md](README.md)를 따른다.

## 1. 동작 방식

등록 상태는 두 곳에 저장된다.

| 정보 | 위치 |
| --- | --- |
| 인스턴스 메타데이터 | PostgreSQL(`mdt-postgres`, 볼륨 `postgres_data`) |
| 인스턴스 파일 | `instances/<id>/`(호스트 `mdt-manager-data/instances`에 바인드 마운트) |

그래서 한 번 등록하면 컨테이너를 다시 만들어도 남는다. 다만 새 서버에 처음 배포하거나 `docker compose down -v`로 DB 볼륨을 지우면 등록이 사라진다.
seed 이미지는 이럴 때 등록을 자동으로 다시 채운다.

```
[빌드 서버]                                      [배포 서버]
models/GShinSung/Lifter ─┐
instances.txt            ├─ docker build ─▶ mdt-seed:<버전> ─ push ─▶ 레지스트리 ─ pull ─▶ compose의
seed-instances.sh        │                                                              mdt-seed-instances 서비스
Dockerfile.seed         ─┘
```

compose의 `mdt-seed-instances` 서비스는 mdt-manager가 healthy가 된 뒤 한 번 실행되고 끝난다. `instances.txt`의 각 인스턴스를 등록하는 순서는 다음과 같다.

1. 이미지 안의 `/seed/models/<id>`를 작업 디렉터리에 복사한다. `/seed/secrets/<id>.env`가 있으면 그 사본에 `env.file`로 넣는다(6장).
2. 복사한 디렉터리의 **내용**을 zip으로 묶는다. zip 안에 최상위 디렉터리를 만들지 않는다. `mdt add instance`와 같은 형식이다.
3. `POST http://mdt-manager:12985/instance-manager/instances`로 보낸다. 폼 필드는 `id`, `port`(선택), `bundle`(zip)이다.
4. 응답이 201이면 등록된 것이다. 409면 이미 등록된 것으로 보고 건너뛴다. 그 밖의 응답이면 오류를 출력하고 실패 코드로 끝난다.

이미 등록된 인스턴스는 건너뛰므로, `docker compose up`을 할 때마다 이 서비스가 다시 실행되어도 안전하다.
mdt-manager 설정에서 `auto-start`가 켜져 있으면, 새로 등록된 인스턴스는 등록 직후 시작된다.

seed 이미지는 mdt-manager 이미지를 기반으로 만든다. 등록에 필요한 `python3`(zip 만들기)과 `curl`(전송)이 이미 들어 있고, 레이어를 공유하므로 늘어나는 크기는 모델 파일만큼이다.
`mdt` CLI를 쓰지 않는 이유는 두 가지다. CLI는 zip을 모델 디렉터리의 부모 디렉터리에 만들기 때문에 읽기 전용 위치에서는 실패하고, CLI JAR과 접속 설정을 따로 넣어야 한다.

## 2. 이 문서의 가정

| 항목 | 값 |
| --- | --- |
| 미리 등록할 인스턴스 | `Lifter` (모델: `$MDT_HOME/models/GShinSung/Lifter`) |
| 인스턴스 포트 | `19146` (`models/GShinSung/scripts.sh`에서 쓰는 값) |
| 버전 | `1.7.2` (mdt-manager 이미지와 같은 태그) |
| seed 빌드 디렉터리 | `~/mdt/docker-compose/mdt-seed/` |
| 레지스트리 경로 | `<레지스트리/경로>` (예: `registry.example.com/mdt`) |

Lifter 모델은 `model.json`과 `config.json`으로만 되어 있다. `env.file`과 `uv_projects.txt`는 없다.

**포트 주의:** compose의 mdt-manager는 인스턴스 포트로 `19000-19050`만 공개한다. 19146을 그대로 쓰려면 5장처럼 포트 매핑을 하나 추가한다.
매핑을 바꾸고 싶지 않다면 `instances.txt`에서 범위 안의 비어 있는 포트를 고른다.

## 3. seed 빌드 디렉터리 만들기

```
~/mdt/docker-compose/mdt-seed/
├── Dockerfile.seed
├── .dockerignore
├── instances.txt          # 등록할 인스턴스 목록
├── seed-instances.sh      # 등록 스크립트
└── models/                # 빌드할 때마다 build_seed.sh가 다시 채운다
    └── Lifter/
```

### 3.1 instances.txt

한 줄에 인스턴스 하나씩 `<id> [port]` 형식으로 적는다. 모델은 `models/<id>/`에 있어야 한다. `#`로 시작하는 줄과 빈 줄은 무시한다. 포트를 생략하면 mdt-manager가 정한다.

```
# <id> [port]
Lifter 19146
```

### 3.2 seed-instances.sh

```bash
#!/bin/bash
# instances.txt의 인스턴스를 mdt-manager에 등록한다. 이미 등록된 인스턴스(409)는 건너뛴다.
set -euo pipefail

SEED_DIR="${SEED_DIR:-/seed}"
MANAGER_URL="${MANAGER_URL:-http://mdt-manager:12985/instance-manager}"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# add <id> [port]
add() {
    local id="$1" port="${2:-}"
    local src="$WORK/src/$id"

    if [ ! -d "$SEED_DIR/models/$id" ]; then
        echo "오류: 모델 디렉터리가 없습니다: $SEED_DIR/models/$id" >&2
        exit 1
    fi
    # 이미지 안의 모델은 그대로 두고 사본에 env.file을 넣는다.
    mkdir -p "$WORK/src"
    cp -r "$SEED_DIR/models/$id" "$src"
    if [ -f "$SEED_DIR/secrets/$id.env" ]; then
        cp "$SEED_DIR/secrets/$id.env" "$src/env.file"
    fi

    # 디렉터리의 내용을 zip 루트에 담는다(mdt add instance와 같은 형식).
    python3 -c 'import shutil, sys; shutil.make_archive(sys.argv[1], "zip", sys.argv[2])' "$WORK/$id" "$src"

    local args=(-F "id=$id" -F "bundle=@$WORK/$id.zip")
    if [ -n "$port" ]; then
        args+=(-F "port=$port")
    fi
    local code
    code=$(curl -sS -o "$WORK/resp" -w '%{http_code}' -X POST "${args[@]}" "$MANAGER_URL/instances")
    case "$code" in
        201) echo "등록: $id" ;;
        409) echo "이미 등록됨, 건너뜀: $id" ;;
        *)   echo "등록 실패: $id (HTTP $code)" >&2
             cat "$WORK/resp" >&2
             exit 1 ;;
    esac
}

while read -r id port _ || [ -n "${id:-}" ]; do
    if [ -z "${id:-}" ] || [[ "$id" == \#* ]]; then
        continue
    fi
    add "$id" "${port:-}"
done < "$SEED_DIR/instances.txt"
```

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `MANAGER_URL` | `http://mdt-manager:12985/instance-manager` | 관리자 주소. compose 서비스 이름과 관리자 포트가 다르면 바꾼다. |
| `SEED_DIR` | `/seed` | `models/`, `instances.txt`, `secrets/`가 있는 디렉터리 |

curl에는 시간 제한을 두지 않았다. 모델에 `uv_projects.txt`가 있으면 관리자가 등록할 때 Python 가상환경을 만들므로 오래 걸릴 수 있기 때문이다.

### 3.3 Dockerfile.seed

```dockerfile
# python3와 curl이 있는 mdt-manager 이미지를 기반으로 한다. 관리자와 같은 버전을 쓴다.
ARG MDT_VERSION
FROM mdt-manager:${MDT_VERSION}

COPY --chown=mdt:mdt models/           /seed/models/
COPY --chown=mdt:mdt instances.txt     /seed/instances.txt
COPY --chown=mdt:mdt seed-instances.sh /seed/seed-instances.sh

# 관리자 JVM을 띄우지 않으므로 기반 이미지의 HEALTHCHECK를 끈다.
HEALTHCHECK NONE
ENTRYPOINT ["/usr/bin/tini", "--", "/bin/bash", "/seed/seed-instances.sh"]
CMD []
```

### 3.4 .dockerignore

이미지에 들어가면 안 되는 파일을 뺀다. `env.file`은 비밀 정보가 들어 있을 수 있어 항상 뺀다(6장).

```
**/env.file
**/.venv
**/__pycache__
**/*.bak*
**/*.code-workspace
```

### 3.5 build_seed.sh

모델을 빌드 디렉터리로 복사하고 이미지를 만든다. 복사본을 쓰는 이유는 빌드 컨텍스트가 `models/` 아래 전체를 가져가지 않게 하려는 것이다.

```bash
#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:-${MDT_BUILD_VERSION:-}}"
if [ -z "$VERSION" ]; then
    echo "사용법: $0 <버전>   (또는 MDT_BUILD_VERSION 설정)" >&2
    exit 1
fi
: "${MDT_HOME:?MDT_HOME must be set}"

rm -rf models && mkdir models
cp -rL "$MDT_HOME/models/GShinSung/Lifter" models/Lifter

docker build -f Dockerfile.seed --build-arg MDT_VERSION="$VERSION" -t "mdt-seed:$VERSION" .
echo "==> 빌드 완료: mdt-seed:$VERSION"
```

등록할 인스턴스를 늘리려면 `instances.txt`에 줄을 추가하고, 이 스크립트에 `cp -rL` 줄을 추가한다. `models/` 아래 디렉터리 이름은 인스턴스 id와 같아야 한다.

## 4. 빌드와 푸시

기반 이미지 `mdt-manager:1.7.2`가 빌드 서버에 있어야 한다([README.md](README.md) 4장).

```bash
cd ~/mdt/docker-compose/mdt-seed
chmod +x build_seed.sh seed-instances.sh
./build_seed.sh 1.7.2

docker tag  mdt-seed:1.7.2 <레지스트리/경로>/mdt-seed:1.7.2
docker push <레지스트리/경로>/mdt-seed:1.7.2
```

이미지에 들어간 파일은 다음 명령으로 확인할 수 있다. `env.file`이 보이면 안 된다.

```bash
docker run --rm --entrypoint find mdt-seed:1.7.2 /seed -type f
```

## 5. 배포 서버의 compose 설정

`docker-compose.yml`에 두 가지를 반영한다.

**(1) mdt-manager의 포트 매핑에 19146 추가** (2장의 포트 주의 참고)

```yaml
  mdt-manager:
    ports:
      - "12985:12985"
      - "19000-19050:19000-19050"
      - "19146:19146"
```

**(2) seed 서비스 추가**

```yaml
  mdt-seed-instances:
    image: <레지스트리/경로>/mdt-seed:$MDT_BUILD_VERSION
    depends_on:
      mdt-manager:
        condition: service_healthy      # mdt-manager 이미지의 HEALTHCHECK(GET /health)가 통과한 뒤
    restart: "no"                       # 한 번 실행하고 끝난다
```

mdt-manager 이미지도 레지스트리에서 받는다면 mdt-manager 서비스의 `image:`도 같은 레지스트리 경로로 맞춘다. seed 이미지는 `mdt-manager:<버전>`을 기반으로 하므로 두 이미지의 버전을 같게 둔다.

## 6. 비밀 정보(env.file) 다루기

모델의 `env.file`에는 `AUTH_TOKEN` 같은 비밀 값이 들어갈 수 있다. 이 파일을 이미지에 넣으면 이미지를 받을 수 있는 사람은 누구나 꺼내 볼 수 있다.
그래서 `.dockerignore`로 항상 빼고, 배포 서버에서 실행할 때 넣는다.

Lifter에는 `env.file`이 없으므로 이 장은 필요 없다. `env.file`이 있는 모델을 추가할 때 다음처럼 한다.

1. 배포 서버에 `secrets/` 디렉터리를 만들고 `<id>.env`로 둔다. 예: `~/mdt/docker-compose/mdt-seed-secrets/CNC-04.env`
2. 권한을 좁힌다: `chmod 700` (디렉터리), `chmod 600` (파일). 컨테이너의 `mdt` 사용자 UID/GID가 읽을 수 있어야 한다.
3. seed 서비스에 마운트를 추가한다.

```yaml
  mdt-seed-instances:
    volumes:
      - ./mdt-seed-secrets:/seed/secrets:ro
```

`seed-instances.sh`는 `/seed/secrets/<id>.env`가 있으면 번들에 `env.file`로 넣는다. 이 디렉터리는 git 등 버전 관리에 넣지 않는다.

## 7. 실행과 확인

```bash
cd ~/mdt/docker-compose
MDT_BUILD_VERSION=1.7.2 docker compose up -d
docker compose logs mdt-seed-instances
```

처음에는 `등록: Lifter`, 이후 실행부터는 `이미 등록됨, 건너뜀: Lifter`가 출력된다. 실행이 끝난 seed 컨테이너는 `docker compose ps -a`에서 `Exited (0)`으로 남는다. 정상이다.

```bash
mdt list instances                      # Lifter가 보이는지 확인
curl -s http://localhost:12985/instance-manager/instances/Lifter
```

seed 서비스만 다시 실행하려면 다음 명령을 쓴다.

```bash
MDT_BUILD_VERSION=1.7.2 docker compose up mdt-seed-instances
```

## 8. 모델을 고쳤을 때

seed는 이미 등록된 인스턴스를 건너뛴다. 고친 모델로 다시 등록하려면 다음 순서로 한다.

1. 빌드 서버에서 seed 이미지를 다시 빌드하고 푸시한다(4장).
2. 배포 서버에서 기존 인스턴스를 지운다: `mdt remove instance Lifter`
3. 새 이미지를 받고 seed를 다시 실행한다.

```bash
MDT_BUILD_VERSION=1.7.2 docker compose pull mdt-seed-instances
MDT_BUILD_VERSION=1.7.2 docker compose up mdt-seed-instances
```

seed는 등록만 한다. `models/GShinSung/scripts.sh`의 `mdt set parameter Lifter CycleTime --json=7`처럼 등록 뒤에 하는 설정은 포함되지 않으므로, 필요하면 등록 후 따로 실행한다.

## 9. 문제 해결

| 증상 | 원인과 조치 |
| --- | --- |
| seed 서비스가 시작되지 않고 기다린다 | mdt-manager가 아직 healthy가 아니다. `docker ps`에서 상태를 보고, `docker logs mdt-manager`로 기동 오류를 확인한다. |
| `오류: 모델 디렉터리가 없습니다: /seed/models/<id>` | `instances.txt`의 id와 `models/` 아래 디렉터리 이름이 다르거나, `build_seed.sh`에 복사 줄이 없다. 4장의 `find` 명령으로 이미지 내용을 확인한다. |
| `등록 실패: <id> (HTTP 400)` | 모델이 잘못되었거나 id가 규칙(`[A-Za-z0-9_-]`, 1~64자)에 맞지 않는다. 함께 출력된 응답 본문을 확인한다. |
| `등록 실패: <id> (HTTP 000)` | 관리자에 접속하지 못했다. `MANAGER_URL`이 compose의 서비스 이름·포트와 맞는지 확인한다. |
| 409인데 인스턴스가 목록에 없다 | 409는 이미 등록된 경우 외에 인스턴스 상태가 맞지 않을 때도 나온다. 관리자 로그를 확인한다. |
| 등록은 되었는데 호스트에서 인스턴스에 접속되지 않는다 | 인스턴스 포트가 compose에 공개되지 않았다. 5장 (1)을 확인한다. |
| 인스턴스가 `env.file` 값이 없어 실패한다 | 6장의 secrets 마운트와 파일 이름(`<id>.env`)을 확인한다. |
