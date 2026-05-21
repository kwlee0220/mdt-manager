# MDT Manager

MDT(Manufacturing Digital Twin) Instance Manager — [Asset Administration Shell(AAS)](https://industrialdigitaltwin.org/) 기반 디지털 트윈 인스턴스를 등록·관리·실행하는 Spring Boot 서버입니다. ETRI에서 개발하였습니다.

## 개요

MDT Manager는 AAS 디지털 트윈("MDT Instance")의 생명주기를 관리하는 중앙 관리자입니다. 주요 기능은 다음과 같습니다.

- **인스턴스 관리**: MDT Instance의 등록, 조회, 시작/중지, 삭제
- **다중 실행 백엔드**: 인스턴스를 여러 방식으로 실행
  - `jar` — 로컬 JAR 프로세스 (Python `uv`/`venv` 연동 지원)
  - `docker` — Docker 컨테이너 (Harbor 레지스트리 연동)
  - `kubernetes` — Kubernetes Pod (Fabric8 client)
  - `external` — 외부에서 직접 구동되는 인스턴스
- **AAS 레지스트리**: AAS Shell / Submodel Descriptor 레지스트리 제공 (캐싱 지원)
- **AAS 리포지토리**: Shell / Submodel 리포지토리 제공
- **MQTT 발행**: 인스턴스 상태 변화를 MQTT로 발행
- **REST API**: 인스턴스 관리 및 Submodel Element 접근용 HTTP API (`/instance-manager`)

## 요구 사항

- Java 21
- PostgreSQL (또는 MariaDB) — JPA 영속화 백엔드
- Gradle (Spring Boot 3.5.4)
- 실행 백엔드에 따라 Docker / Kubernetes 환경

## 빌드

```bash
# 버전은 MDT_BUILD_VERSION 환경변수로 지정 (미지정 시 "unknown")
export MDT_BUILD_VERSION=1.3.0
./gradlew bootJar
```

`build/libs/mdt-manager-<version>-all.jar` 형태의 실행 가능 JAR이 생성됩니다.

> 이 프로젝트는 형제 디렉토리의 `:utils`(`../../common/utils`)와 `:mdt-client`(`../mdt-client`) 프로젝트에 의존합니다. (`settings.gradle` 참고)

## 실행

```bash
java -jar build/libs/mdt-manager-<version>-all.jar
```

메인 클래스는 [MDTInstanceApplication](src/main/java/mdt/MDTInstanceApplication.java)이며, `instance-manager.auto-start`가 활성화된 경우 기동 시 등록된 인스턴스를 자동으로 시작합니다.

### 주요 설정

`instance-manager` prefix의 Spring 설정 ([MDTInstanceManagerConfiguration](src/main/java/mdt/instance/MDTInstanceManagerConfiguration.java)):

| 설정 | 설명 |
|------|------|
| `instance-manager.type` | 실행 백엔드 (`jar` / `docker` / `kubernetes` / `external`) |
| `instance-manager.mdt-url` | MDT Platform 접속 URL |
| `instance-manager.home-dir` | MDT Manager 홈 디렉토리 |
| `instance-manager.instances-dir` | 인스턴스 디렉토리 |
| `instance-manager.bundles-dir` | 번들 디렉토리 |
| `instance-manager.global-config-file` | 인스턴스 공유 전역 설정 파일 |
| `instance-manager.instance-endpoint-format` | 인스턴스 접속 URL 포맷 |
| `instance-manager.auto-start` | 기동 시 인스턴스 자동 시작 여부 |

JPA(PostgreSQL) 접속 등 전역 설정은 `mdt_global_config.json`(또는 `_mariadb` / `_postgresql` 변형)에 정의합니다.

## Docker 이미지

```bash
# MDT_HOME, MDT_BUILD_VERSION 환경변수 필요
cd docker
./build_image.sh --tag 1.3.0          # 이미지 빌드
./build_image.sh --host-user          # 호스트 UID/GID로 빌드 (볼륨 권한 정합)
./push_image.sh                       # 레지스트리 푸시
```

자세한 옵션은 `./build_image.sh --help` 참고. 베이스 이미지는 `eclipse-temurin:21-jre-jammy`이며 Python 및 `mdtpy`가 함께 설치됩니다 ([docker/Dockerfile](docker/Dockerfile)).

## 프로젝트 구조

```
src/main/java/mdt/
├── MDTInstanceApplication.java   # Spring Boot 진입점
├── controller/                   # REST API 컨트롤러 (instance-manager, registry)
├── instance/                     # 인스턴스 관리 + 실행 백엔드 (jar/docker/k8s/external)
│   └── jpa/                      # JPA 엔티티 / 리포지토리 (Instance Descriptor 등)
├── exector/jar/                  # JAR 인스턴스 실행기
├── registry/                     # AAS Shell / Submodel 레지스트리 (캐싱)
└── repository/                   # AAS Shell / Submodel 리포지토리
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
