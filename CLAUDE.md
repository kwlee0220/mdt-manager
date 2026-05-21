# CLAUDE.md

이 파일은 이 저장소에서 작업하는 Claude Code(claude.ai/code)를 위한 안내입니다.

## 프로젝트 개요

MDT(Manufacturing Digital Twin) Instance Manager — Asset Administration Shell(AAS) 기반 디지털 트윈
인스턴스("MDT Instance")의 등록·실행·관리를 담당하는 Spring Boot 서버이다. ETRI에서 개발하였다.
group은 `etri`, 루트 패키지는 `mdt`이다.

## 기술 스택

- **언어/런타임**: Java 21 (UTF-8 인코딩)
- **프레임워크**: Spring Boot 3.5.4 (web, data-jpa, validation, devtools)
- **빌드**: Gradle (`org.springframework.boot`, `io.spring.dependency-management` 플러그인)
- **영속화**: JPA / Hibernate, PostgreSQL(기본) 또는 MariaDB, hibernate-types-60
- **API 문서**: springdoc-openapi (Swagger UI)
- **실행 백엔드 연동**: Docker(`org.mandas:docker-client` + Jersey), Kubernetes(Fabric8 client)
- **메시징**: Eclipse Paho MQTT
- **HTTP 클라이언트**: OkHttp
- **유틸**: Lombok, JSR305(`@Nullable` 등, compileOnly)

## 빌드 / 실행 명령

```bash
# 빌드 (버전은 환경변수로 주입; 미지정 시 "unknown")
export MDT_BUILD_VERSION=1.3.0
./gradlew bootJar          # build/libs/mdt-manager-<version>-all.jar 생성

./gradlew build            # 전체 빌드 + 테스트
./gradlew test             # 테스트만 실행

# 실행
java -jar build/libs/mdt-manager-<version>-all.jar

# Docker 이미지 (MDT_HOME, MDT_BUILD_VERSION 필요)
cd docker && ./build_image.sh --tag <VERSION>
```

- 버전: `build.gradle`의 `version`은 `MDT_BUILD_VERSION` 환경변수에서 읽는다.
- 의존 버전들은 `gradle.properties`에 중앙 관리된다 (`mdt_version=0.9.1` 등).

## 멀티 프로젝트 의존성 (중요)

`settings.gradle`에 따라 이 프로젝트는 **형제 디렉토리의 다른 Gradle 프로젝트에 의존**한다:

- `:utils` → `../../common/utils`
- `:mdt-client` → `../mdt-client`

따라서 빌드하려면 이 저장소뿐 아니라 위 경로의 프로젝트들도 함께 존재해야 한다.

## 코드 구조

진입점은 [MDTInstanceApplication](src/main/java/mdt/MDTInstanceApplication.java) (`@SpringBootApplication`,
`@ConfigurationPropertiesScan("mdt")`). 기동 후 `auto-start`가 켜져 있으면 등록된 인스턴스를 자동 시작한다.

- `mdt/controller/` — REST 컨트롤러. 진입 컨트롤러는 [MDTInstanceManagerController](src/main/java/mdt/controller/MDTInstanceManagerController.java) (`/instance-manager`). 레지스트리 컨트롤러(`JpaShellRegistryController`, `JpaSubmodelRegistryController`), 예외 처리(`MDTExceptionAdvice`) 포함.
- `mdt/instance/` — 인스턴스 추상화(`AbstractInstance`, `AbstractJpaInstanceManager`)와 실행 백엔드별 구현:
  - `jar/` — 로컬 JAR 프로세스. Python `uv`/`venv` 연동(`PythonUvProjects`, `PythonVenvCreator`).
  - `docker/` — Docker 컨테이너. Harbor 레지스트리 연동(`MDTHarborClient`).
  - `k8s/` — Kubernetes Pod (Fabric8).
  - `external/` — 외부에서 구동되는 인스턴스.
  - `jpa/` — JPA 엔티티/리포지토리(InstanceDescriptor, Submodel/Operation/Parameter Descriptor).
  - `MDTInstanceManagerConfiguration` — `instance-manager.*` 설정 바인딩.
  - `MDTInstanceStatusMqttPublisher`, `MqttConfiguration` — 상태 MQTT 발행.
- `mdt/exector/jar/` — JAR 인스턴스 실행기(`JarInstanceExecutor`, `JarExecutionCommand`, 리스너 등).
- `mdt/registry/` — AAS Shell/Submodel Descriptor 레지스트리. 파일 기반 캐싱 구현 포함.
- `mdt/repository/` — AAS Shell/Submodel 리포지토리(파일 기반, 인메모리 Shell 서비스).

## 설정

- Spring 설정 prefix는 `instance-manager`이며 [MDTInstanceManagerConfiguration](src/main/java/mdt/instance/MDTInstanceManagerConfiguration.java)에 바인딩된다 (`type`, `mdt-url`, `home-dir`, `instances-dir`, `bundles-dir`, `global-config-file`, `instance-endpoint-format`, `auto-start`).
- `type`은 실행 백엔드를 결정한다: `jar` / `docker` / `kubernetes` / `external`.
- DB 등 인스턴스 공유 전역 설정은 `mdt_global_config.json`(및 `_mariadb`, `_postgresql` 변형)에 둔다. 이 파일은 접속 비밀번호를 포함하므로 커밋 대상이 아니다.
- `mdt_cert.p12`(인증서)도 비밀 정보이므로 커밋하지 않는다.

## 규약

- 새 코드는 주변 코드의 스타일을 따른다. 필드는 `m_` prefix를 사용하는 경우가 있다(예: `MDTInstanceManagerConfiguration`).
- 작성자 표기(`@author Kang-Woo Lee (ETRI)`)와 한글 주석이 혼재한다. 기존 파일 수정 시 해당 관례를 유지한다.
- `commons-logging`은 전역 제외되어 있다.
