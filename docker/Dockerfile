FROM boxcutter/python:3.11-slim-jammy

# UID/GID 설정 (빌드 시 --build-arg UID=1000 형태로 오버라이드 가능)
ARG UID=1000
ARG GID=1000

# Eclipse Temurin JRE 21 설치
RUN apt-get update && apt-get install -y \
    wget gnupg ca-certificates curl unzip \
		libpq-dev gcc python3-dev libgl1-mesa-glx libglib2.0-0 \
 && mkdir -p /etc/apt/keyrings \
 && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb jammy main" > /etc/apt/sources.list.d/adoptium.list \
 && apt-get update \
 && apt-get install -y temurin-21-jre \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# uv (Python 패키지 매니저) 설치 (시스템 경로에 복사하여 모든 사용자가 사용 가능)
RUN curl -LsSf https://astral.sh/uv/install.sh | sh \
 && cp /root/.local/bin/uv /usr/local/bin/

# Python 패키지 설치
# RUN pip install --no-cache-dir numpy pandas simpy scipy isodate pyyaml dataclasses_json psycopg2-binary paho-mqtt python-dateutil opencv-python matplotlib PySide6 mdtpy
RUN pip install --no-cache-dir pyyaml python-dateutil mdtpy

ENV MDT_HOME=/mdt

# mdt 사용자 생성 (지정된 UID/GID)
RUN groupadd -g ${GID} mdt \
 && useradd -u ${UID} -g mdt -m -s /bin/bash mdt

RUN mkdir -p /mdt/mdt-manager/config
RUN mkdir -p /mdt/mdt-manager/instances
RUN mkdir -p /mdt/mdt-manager/bundles

WORKDIR /mdt/mdt-manager
COPY mdt-manager-all.jar /mdt/mdt-manager/mdt-manager-all.jar
COPY mdt-instance-all.jar /mdt/mdt-manager/mdt-instance-all.jar

RUN chown -R mdt:mdt /mdt

USER mdt

# ENTRYPOINT ["java", "-jar", "../mdt-manager-all.jar"]
CMD ["java", "-jar", "mdt-manager-all.jar"]
