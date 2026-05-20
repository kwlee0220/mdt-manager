FROM eclipse-temurin:21-jre-jammy

# UID/GID 설정 (빌드 시 --build-arg UID=1000 형태로 오버라이드 가능)
ARG UID=1000
ARG GID=1000

# Python 및 필요한 패키지 설치
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-pip \
 && pip3 install --no-cache-dir pyyaml python-dateutil mdtpy \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

ENV MDT_HOME=/mdt

# mdt 사용자 생성 (지정된 UID/GID) 및 작업 디렉토리 준비
RUN groupadd -g ${GID} mdt \
 && useradd -u ${UID} -g mdt -m -s /bin/bash mdt \
 && mkdir -p /mdt/mdt-manager/{config,instances,bundles}

WORKDIR /mdt/mdt-manager
COPY mdt-manager-all.jar  /mdt/mdt-manager/mdt-manager-all.jar
COPY mdt-instance-all.jar /mdt/mdt-manager/mdt-instance-all.jar

RUN chown -R mdt:mdt /mdt

USER mdt

CMD ["java", "-XX:MaxRAMPercentage=75.0", \
     "-jar", "/mdt/mdt-manager/mdt-manager-all.jar"]
