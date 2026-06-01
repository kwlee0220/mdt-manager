FROM eclipse-temurin:21-jre-jammy

# UID/GID 설정 (빌드 시 --build-arg UID=1000 형태로 오버라이드 가능)
ARG UID=1000
ARG GID=1000

# Python 및 필요한 패키지 설치
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-pip \
 && pip3 install --no-cache-dir uv==0.10.9 pyyaml==6.0.3 python-dateutil==2.8.2 mdtpy==0.2.4 \
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
# 아래 두 JAR은 docker/build_image.sh가 $MDT_HOME/mdt-manager/에서 빌드 컨텍스트로
# 복사해 둔 것에 의존한다(빌드 후 trap으로 삭제됨). Dockerfile만 직접 빌드하면 JAR이
# 없어 실패하므로, 반드시 build_image.sh를 경유하거나 동일 위치에 JAR을 먼저 두어야 한다.
COPY --chown=mdt:mdt mdt-manager-all.jar  /mdt/mdt-manager/mdt-manager-all.jar
COPY --chown=mdt:mdt mdt-instance-all.jar /mdt/mdt-manager/mdt-instance-all.jar

USER mdt

CMD ["java", "-XX:MaxRAMPercentage=75.0", \
     "-jar", "/mdt/mdt-manager/mdt-manager-all.jar"]
