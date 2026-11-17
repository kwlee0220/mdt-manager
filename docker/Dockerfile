# 기반 이미지를 digest로 고정해, 같은 소스로 빌드하면 같은 JRE·OS 패키지가 들어가게 한다.
# 대신 보안 갱신을 자동으로 받지 않으므로 주기적으로 갱신한다:
#   docker buildx imagetools inspect eclipse-temurin:21-jre-jammy   # 'Digest:' 값으로 아래를 바꾼다
FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269

# UID/GID 설정 (빌드 시 --build-arg UID=1000 형태로 오버라이드 가능)
ARG UID=1000
ARG GID=1000

# Python 및 필요한 패키지 설치
# tini: PID 1로 java를 감싸 인스턴스가 남긴 고아 프로세스를 거둔다(아래 ENTRYPOINT).
# curl: 아래 HEALTHCHECK에서 쓴다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-pip tini curl \
 && pip3 install --no-cache-dir uv==0.10.9 pyyaml==6.0.3 python-dateutil==2.8.2 mdtpy==0.3.0 \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

ENV MDT_HOME=/mdt

# mdt 사용자 생성 (지정된 UID/GID) 및 작업 디렉토리 준비
# GID/UID가 베이스 이미지에 이미 점유돼 있으면(예: --host-user로 GID 100=users 전달)
# 조용히 재사용하지 않고 충돌 원인을 명확히 알리며 빌드를 중단한다.
RUN if getent group "${GID}" >/dev/null; then \
        echo "오류: GID ${GID}가 이미 사용 중입니다 ($(getent group "${GID}" | cut -d: -f1)). --gid 로 비어 있는 GID를 지정하세요." >&2; \
        exit 1; \
    fi \
 && if getent passwd "${UID}" >/dev/null; then \
        echo "오류: UID ${UID}가 이미 사용 중입니다 ($(getent passwd "${UID}" | cut -d: -f1)). --uid 로 비어 있는 UID를 지정하세요." >&2; \
        exit 1; \
    fi \
 && groupadd -g ${GID} mdt \
 && useradd -u ${UID} -g mdt -m -s /bin/bash mdt \
 && mkdir -p /mdt/mdt-manager/config /mdt/mdt-manager/instances /mdt/mdt-manager/bundles \
 && chown -R mdt:mdt /mdt

WORKDIR /mdt/mdt-manager
# 아래 두 JAR은 빌드 컨텍스트에 있어야 한다. docker/build_image.sh는 임시 디렉터리를 컨텍스트로 만들어
# Dockerfile과 $MDT_HOME/mdt-manager/의 JAR(버전 확인 후)을 복사해 빌드하고, 끝나면 그 디렉터리만 지운다.
# docker build를 직접 쓰려면 이 Dockerfile과 두 JAR을 한 디렉터리에 두고 그 디렉터리를 컨텍스트로 지정한다.
COPY --chown=mdt:mdt mdt-manager-all.jar  /mdt/mdt-manager/mdt-manager-all.jar
COPY --chown=mdt:mdt mdt-instance-all.jar /mdt/mdt-manager/mdt-instance-all.jar

USER mdt

# 관리자 REST 포트. application.yml의 server.port를 바꾸면 MDT_MANAGER_PORT도 같이 바꾼다.
# jar 방식 인스턴스의 포트(compose 예: 19000-19050)는 배포마다 달라 여기서는 적지 않는다.
ENV MDT_MANAGER_PORT=12985
EXPOSE 12985

# 관리자의 GET /health가 응답하는지 확인한다. 기동(인스턴스 자동 시작 포함)에 시간이 걸리므로 start-period를 둔다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${MDT_MANAGER_PORT}/health" > /dev/null || exit 1

# java가 PID 1이면 jar 인스턴스가 남긴 고아 프로세스(uv, python 등)가 좀비로 쌓인다.
# tini가 PID 1이 되어 이들을 거두고, SIGTERM 등은 java에 전달한다.
ENTRYPOINT ["/usr/bin/tini", "--"]

# 관리자 JVM 옵션. 컨테이너 메모리 제한과 인스턴스 JVM(executor.heap-size)의 몫을 고려해
# 'docker run -e JAVA_OPTS=...'이나 compose의 environment로 바꾼다.
# JDK_JAVA_OPTIONS는 쓰지 않는다. java가 자동으로 읽어 컨테이너 안의 인스턴스 JVM에도 적용되기 때문이다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /mdt/mdt-manager/mdt-manager-all.jar"]
