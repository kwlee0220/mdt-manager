# MDT Manager

MDT(Manufacturing Digital Twin) Instance Manager — [Asset Administration Shell(AAS)](https://industrialdigitaltwin.org/) 기반 디지털 트윈 인스턴스를 등록·관리·실행하는 Spring Boot 서버입니다. ETRI에서 개발하였습니다.

## 개요

MDT Manager는 AAS 디지털 트윈("MDT Instance")의 생명주기를 관리하는 중앙 관리자입니다. 주요 기능은 다음과 같습니다.

- **인스턴스 관리**: MDT Instance의 등록, 조회, 시작/중지, 삭제
- **다중 실행 백엔드**: 인스턴스를 여러 방식으로 실행
  - `jar` — 로컬 JAR 프로세스 (Python `uv` 연동 지원)
  - `docker` — Docker 컨테이너
  - `external` — 외부에서 직접 구동되는 인스턴스
- **AAS 레지스트리**: 등록된 인스턴스의 AAS Shell / Submodel Descriptor를 제공하는 REST API (`/aas_registry`)
- **MQTT 발행**: 인스턴스 상태 변화를 MQTT로 발행
- **REST API**: 인스턴스 관리 및 Submodel Element 접근용 HTTP API (`/instance-manager`)

## 요구 사항

- Java 21
- PostgreSQL — JPA 영속화 백엔드(PostgreSQL 전용, MariaDB는 지원하지 않음)
- Gradle (Spring Boot 3.5.4)
- 실행 백엔드에 따라 Docker 환경

## 빌드

```bash
# 버전은 MDT_BUILD_VERSION 환경변수로 지정 (미지정 시 "unknown")
export MDT_BUILD_VERSION=1.3.0
gradle bootJar
```

`build/libs/mdt-manager-<version>-all.jar` 형태의 실행 가능 JAR이 생성됩니다.

> 이 프로젝트는 형제 디렉토리의 `:utils`(`../../common/utils`)와 `:mdt-client`(`../mdt-client`) 프로젝트에 의존합니다. (`settings.gradle` 참고)

## 실행

```bash
java -jar build/libs/mdt-manager-<version>-all.jar
```

메인 클래스는 [MDTInstanceApplication](src/main/java/mdt/MDTInstanceApplication.java)이며, `instance-manager.auto-start`가 활성화된 경우 등록된 인스턴스를 바로 시작합니다. 매니저 기동 시 자동 시작은 `jar` 백엔드에만 적용됩니다.

### 주요 설정

`instance-manager` prefix의 Spring 설정 ([MDTInstanceManagerConfiguration](src/main/java/mdt/instance/MDTInstanceManagerConfiguration.java)):

| 설정 | 설명 |
|------|------|
| `instance-manager.type` | 실행 백엔드 (`jar` / `docker` / `external`) |
| `instance-manager.mdt-url` | MDT Platform 접속 URL |
| `instance-manager.home-dir` | MDT Manager 홈 디렉토리 |
| `instance-manager.instances-dir` | 인스턴스 디렉토리 |
| `instance-manager.bundles-dir` | 번들 디렉토리 |
| `instance-manager.global-config-file` | 인스턴스 공유 전역 설정 파일 |
| `instance-manager.instance-endpoint-format` | 인스턴스 접속 URL 포맷 |
| `instance-manager.auto-start` | 인스턴스 자동 시작 여부 (매니저 기동 시 자동 시작은 `jar` 백엔드만) |

JPA(PostgreSQL) 접속 등 전역 설정은 `mdt_global_config.json`(또는 `_postgresql` 변형)에 정의합니다.

## Docker 이미지

JAR 빌드부터 이미지 빌드, compose 실행, 레지스트리 업로드, 문제 해결까지의 전체 절차는 [docker/README.md](docker/README.md)에 있습니다.

```bash
# MDT_HOME, MDT_BUILD_VERSION 환경변수 필요
cd docker
./build_image.sh --tag 1.3.0          # 이미지 빌드
./build_image.sh --host-user          # 호스트 UID/GID로 빌드 (볼륨 권한 정합)
./push_image.sh --repo <REPO> --tag 1.3.0   # 레지스트리 푸시 (--latest로 latest도)
```

자세한 옵션은 `./build_image.sh --help` 참고. 베이스 이미지는 `eclipse-temurin:21-jre-jammy`(digest로 고정, 갱신 방법은 `Dockerfile` 첫 주석 참고)이며 Python 및 `mdtpy`가 함께 설치됩니다 ([docker/Dockerfile](docker/Dockerfile)).

- `build_image.sh`는 `$MDT_HOME/mdt-manager/`의 `mdt-manager-all.jar`, `mdt-instance-all.jar`를 이미지에 넣습니다. 두 JAR의 버전이 `--tag`와 다르면 빌드하지 않습니다.
- 컨테이너의 PID 1은 `tini`입니다. jar 방식 인스턴스가 남긴 자식 프로세스를 거두고, 종료 신호는 관리자 JVM에 전달합니다.

### 실행 전 준비

compose는 `config`, `instances`, `logs`를 호스트 디렉터리에 바인드 마운트합니다(`~/mdt/docker-compose/mdt-manager-data/` 아래).
호스트에 없는 디렉터리는 Docker가 root 소유로 만들어, 컨테이너의 `mdt` 사용자가 쓰지 못합니다. 이미지 안의 디렉터리 설정은 마운트에 가려져 효과가 없습니다.

1. 마운트할 디렉터리를 컨테이너를 띄울 호스트 사용자로 미리 만듭니다.
   ```bash
   mkdir -p ~/mdt/docker-compose/mdt-manager-data/{config,instances,logs}
   ```
2. 이미지를 그 사용자의 UID/GID로 빌드합니다(`./build_image.sh --host-user --tag <버전>`). 기본값(UID/GID 1000)으로 빌드했다면 디렉터리 소유자도 1000이어야 합니다.

이미지에는 `GET /health`를 확인하는 `HEALTHCHECK`가 있어 `docker ps`에 상태(`healthy` 등)가 표시됩니다. 관리자 포트를 바꾸면(`server.port`) 환경 변수 `MDT_MANAGER_PORT`도 같이 바꿉니다.

### 메모리

jar 방식 인스턴스는 관리자 컨테이너 안에서 별도 JVM으로 실행되므로, 컨테이너 메모리는 관리자와 모든 인스턴스가 함께 씁니다.
관리자 JVM의 힙은 환경 변수 `JAVA_OPTS`(기본값 `-XX:MaxRAMPercentage=75.0`)로 정하고, 인스턴스 JVM의 힙은 `executor.heap-size`로 정합니다.

컨테이너에 메모리 제한이 없으면 `MaxRAMPercentage`는 호스트 메모리를 기준으로 계산됩니다. 제한을 두고, 관리자 몫은 인스턴스 몫을 뺀 범위에서 정합니다.

```yaml
# docker-compose.yml 예: 8GB 제한, 인스턴스 힙 512MB × 최대 8개를 남기고 관리자는 최대 2GB
services:
  mdt-manager:
    mem_limit: 8g
    environment:
      - JAVA_OPTS=-Xmx2g
```

인스턴스 JVM은 힙 외에도 메모리를 쓰므로(스레드 스택, 메타스페이스 등) 여유를 둡니다. 위 값은 예시이며 실제 인스턴스 수와 모델 크기에 맞춰 정합니다.

## 프로젝트 구조

```
src/main/java/mdt/
├── MDTInstanceApplication.java   # Spring Boot 진입점
├── controller/                   # REST API 컨트롤러 (instance-manager, registry)
├── instance/                     # 인스턴스 관리 + 실행 백엔드 (jar/docker/external)
│   └── jpa/                      # JPA 엔티티 / 리포지토리 (Instance Descriptor 등)
├── exector/jar/                  # JAR 인스턴스 실행기
├── registry/                     # 파일 기반 레지스트리 구현 (현재 쓰이지 않음)
└── repository/                   # JPA 리포지토리 묶음 `Repositories` (그 밖의 파일 기반 구현은 쓰이지 않음)
```

## REST API

기본 경로는 `/instance-manager` ([MDTInstanceManagerController](src/main/java/mdt/controller/MDTInstanceManagerController.java)). 주요 엔드포인트:

- `GET    /instances`, `GET /instances/{id}` — 인스턴스 목록/조회
- `POST   /instances` — 인스턴스 등록
- `DELETE /instances/{id}` — 인스턴스 삭제
- `PUT    /instances/{id}/start`, `PUT /instances/{id}/stop` — 시작/중지
- `GET    /instances/{id}/model/...` — 모델/submodel/parameter/operation 조회
- `GET/PUT /submodel-element` — Submodel Element 접근

OpenAPI(Swagger) 문서는 springdoc 설정에 따라 `/swagger-ui.html`에서 확인할 수 있습니다.
