# mdt-manager Docker 이미지 만들기

이 문서는 mdt-manager Docker 이미지를 빌드하고, compose로 실행하고, 레지스트리에 올리는 절차를 설명한다.
이미지에는 관리자 JAR(`mdt-manager-all.jar`)과 인스턴스 JAR(`mdt-instance-all.jar`)이 함께 들어가며,
jar 방식 인스턴스는 관리자 컨테이너 안에서 별도 JVM으로 실행된다.

아래 예의 경로는 다음과 같다. 환경에 맞게 바꿔 읽는다.

| 표기 | 의미 | 예 |
| --- | --- | --- |
| `~/development/mdt` | MDT 소스를 받아 둔 위치 | `mdt-manager`, `mdt-instance`, `mdt-client`가 이 아래에 있다 |
| `$MDT_HOME` | MDT 설치 디렉터리 | `/home/kwlee/mdt` |
| `~/mdt/docker-compose` | compose 파일과 마운트 데이터가 있는 배포 디렉터리 | `docker-compose.yml`, `mdt-manager-data/` |
| `1.7.2` | 빌드할 버전 | |

## 1. 준비

- Docker, Gradle(래퍼 없음, 시스템 `gradle` 사용), Java 21
- 형제 프로젝트: mdt-manager는 `../../common/utils`와 `../mdt-client`를 함께 빌드하므로 둘 다 받아 두어야 한다.
- 환경 변수 `MDT_HOME`: `build_image.sh`는 `$MDT_HOME/mdt-manager/`에서 JAR을 가져온다.

## 2. JAR 빌드

두 JAR을 같은 버전으로 빌드한다. 버전은 환경 변수 `MDT_BUILD_VERSION`으로 정한다(없으면 `unknown`).

```bash
export MDT_BUILD_VERSION=1.7.2

cd ~/development/mdt/mdt-manager  && gradle bootJar     # build/libs/mdt-manager-1.7.2-all.jar
cd ~/development/mdt/mdt-instance && gradle shadowJar   # build/libs/mdt-instance-1.7.2-all.jar
```

## 3. 설치 위치에 연결

빌드한 JAR을 `$MDT_HOME/mdt-manager/`에 정해진 이름으로 둔다. 심볼릭 링크로 연결해 두면, 같은 버전을 다시 빌드할 때는 이 단계를 건너뛸 수 있다.
버전을 바꾸면 링크를 다시 건다.

```bash
ln -sfn ~/development/mdt/mdt-manager/build/libs/mdt-manager-${MDT_BUILD_VERSION}-all.jar   $MDT_HOME/mdt-manager/mdt-manager-all.jar
ln -sfn ~/development/mdt/mdt-instance/build/libs/mdt-instance-${MDT_BUILD_VERSION}-all.jar $MDT_HOME/mdt-manager/mdt-instance-all.jar
```

## 4. 이미지 빌드

```bash
cd ~/development/mdt/mdt-manager/docker
./build_image.sh --tag 1.7.2                 # 이미지 안의 mdt 사용자: UID/GID 1000
./build_image.sh --host-user --tag 1.7.2     # 현재 호스트 사용자의 UID/GID
```

결과는 `mdt-manager:1.7.2` 이미지다. 옵션은 `./build_image.sh --help`로 볼 수 있다.

| 옵션·환경 변수 | 설명 |
| --- | --- |
| `--tag`, `-t <버전>` | 이미지 태그. 생략하면 `MDT_BUILD_VERSION` |
| `--host-user`, `-H` | 이미지의 `mdt` 사용자를 현재 호스트 사용자의 UID/GID로 만든다. 마운트 디렉터리의 소유자와 맞출 때 쓴다. |
| `--uid <UID>`, `--gid <GID>` | UID/GID를 직접 지정한다. `--host-user`와 함께 쓸 수 없다. |
| `MDT_IMAGE_NAME` | 이미지 이름(기본값 `mdt-manager`). `push_image.sh`에도 같은 값을 쓴다. |

스크립트는 빌드 전에 다음을 확인한다.

- **JAR 버전:** 두 JAR의 버전이 태그와 같은지 확인한다. 관리자 JAR은 매니페스트의 `Implementation-Version`에서, 인스턴스 JAR은 심볼릭 링크가 가리키는 실제 파일 이름(`-<버전>-all.jar`)에서 읽는다. 다르면 빌드하지 않는다.
- **빌드 위치:** 임시 디렉터리를 빌드 컨텍스트로 쓰므로 `docker/` 폴더의 파일은 바뀌지 않는다.
- **이전 이미지 정리:** 같은 태그로 다시 빌드하면, 태그를 잃은 이전 이미지만 지운다. 다른 태그가 남아 있거나 컨테이너가 쓰고 있으면 지우지 않는다.

이미지 구성은 [Dockerfile](Dockerfile)에 있다.

- **기반 이미지:** `eclipse-temurin:21-jre-jammy`를 digest로 고정해 두었다. 보안 갱신을 받으려면 `Dockerfile` 첫 주석의 방법으로 digest를 바꾼다.
- **함께 설치하는 것:** Python, `uv`, `mdtpy`, `tini`, `curl`
- **PID 1:** `tini`가 PID 1이 되어 인스턴스가 남긴 자식 프로세스를 거두고, 종료 신호는 관리자 JVM에 전달한다.
- **헬스체크:** 30초마다 `GET /health`를 확인한다.

## 5. 실행

### 5.1 처음 한 번: 마운트 디렉터리 만들기

compose는 `config`, `instances`, `logs`를 호스트 디렉터리에 바인드 마운트한다.
호스트에 없는 디렉터리는 Docker가 root 소유로 만들어 컨테이너의 `mdt` 사용자가 쓰지 못하므로, 컨테이너를 띄울 사용자로 미리 만든다.

```bash
mkdir -p ~/mdt/docker-compose/mdt-manager-data/{config,instances,logs}
```

디렉터리 소유자의 UID/GID는 이미지를 빌드할 때 쓴 값과 같아야 한다. 기본값으로 빌드했다면 1000이고, `--host-user`로 빌드했다면 빌드한 사용자다.
관리자 설정 `application.yml`은 `config/` 아래에 둔다.

### 5.2 compose로 띄우기

```bash
cd ~/mdt/docker-compose
MDT_BUILD_VERSION=1.7.2 docker compose up -d mdt-manager
docker ps                     # STATUS에 (healthy)가 나오면 정상
docker logs -f mdt-manager
```

`docker-compose.yml`의 `image: mdt-manager:$MDT_BUILD_VERSION`이 4장에서 만든 이미지를 가리킨다.

### 5.3 메모리 설정

관리자와 jar 방식 인스턴스가 컨테이너 메모리를 함께 쓴다. 설정할 곳은 세 군데다.

| 설정 | 위치 | 현재 값 |
| --- | --- | --- |
| 컨테이너 메모리 제한 | `docker-compose.yml`의 `mem_limit` | `14g` |
| 관리자 JVM 힙 | `docker-compose.yml`의 환경 변수 `JAVA_OPTS` | `-Xmx2g` |
| 인스턴스 JVM 힙 | `mdt-manager-data/config/application.yml`의 `executor.heapSize` | `512m` |

현재 값은 동시 인스턴스 12개 기준이며, 계산식은 `docker-compose.yml`의 `mem_limit` 위 주석에 있다.

```
mem_limit ≥ 관리자 힙 + 인스턴스 수 × (인스턴스 힙 + 힙 외 약 0.3GB) + 여유
```

- **값을 다시 정할 때:** 인스턴스 수나 `heapSize`를 바꾸면 `mem_limit`을 다시 계산한다.
- **제한에 걸리면:** 컨테이너 전체가 OOM으로 종료되므로, 처음에는 넉넉하게 잡고 `docker stats`로 실제 사용량을 확인한다.
- **`JAVA_OPTS`를 비우면:** 이미지 기본값 `-XX:MaxRAMPercentage=75.0`이 적용된다.

### 5.4 포트

관리자 REST 포트는 12985이고, jar 방식 인스턴스는 compose가 연 `19000-19050` 범위의 포트를 쓴다.
관리자 포트를 바꾸려면 `application.yml`의 `server.port`, compose의 포트 매핑, 환경 변수 `MDT_MANAGER_PORT`(헬스체크가 쓴다)를 함께 바꾼다.

## 6. 레지스트리에 올리기(선택)

```bash
cd ~/development/mdt/mdt-manager/docker
./push_image.sh --repo <레지스트리/경로> --tag 1.7.2            # <레지스트리/경로>/mdt-manager:1.7.2
./push_image.sh --repo <레지스트리/경로> --tag 1.7.2 --latest   # :latest도 함께
```

- **로그인:** 레지스트리에 미리 `docker login` 해 둔다.
- **compose 이미지 경로:** 다른 호스트에서 이 이미지를 받아 쓰려면 compose의 `image:`를 올린 경로로 바꾼다. 예를 들어 `docker-compose-full.yml`은 `kwlee0220/mdt-manager`를 쓴다.

## 7. 문제 해결

| 증상 | 원인과 조치 |
| --- | --- |
| `오류: $MDT_HOME 환경변수가 설정되어 있어야 합니다.` | `export MDT_HOME=...`을 설정한다. |
| `오류: JAR이 없습니다: ...` | 2장·3장을 다시 확인한다. 링크가 가리키는 파일이 없어도 이 오류가 난다. |
| `오류: JAR 버전(x)이 이미지 태그(y)와 다릅니다` | `--tag`를 JAR 버전과 맞추거나, 원하는 버전으로 JAR을 다시 빌드하고 링크를 바꾼다. |
| `경고: JAR 버전을 확인할 수 없습니다` | 매니페스트에 버전이 없고 파일 이름에도 버전이 없는 JAR이다. 빌드는 진행되지만 버전을 보장하지 못하므로, 버전이 붙은 파일에 링크하는 편이 좋다. |
| 빌드 중 `오류: GID ... 가 이미 사용 중입니다` 또는 `UID ...` | 기반 이미지에 이미 있는 UID/GID다. `--uid`·`--gid`로 비어 있는 값을 지정한다. |
| 컨테이너 로그에 권한 오류(`Permission denied`) | 마운트 디렉터리의 소유자가 이미지의 UID/GID와 다르다. 5.1장을 확인한다. |
| `docker ps`에 `(unhealthy)` | 관리자가 `GET /health`에 응답하지 않는다. `docker logs mdt-manager`로 기동 오류를 확인한다. 포트를 바꿨다면 `MDT_MANAGER_PORT`도 바꿨는지 확인한다. |
| 컨테이너가 갑자기 종료(`OOMKilled`) | 5.3장의 메모리 설정을 다시 계산한다. `docker inspect mdt-manager --format '{{.State.OOMKilled}}'`로 확인할 수 있다. |
