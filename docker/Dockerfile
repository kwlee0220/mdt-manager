FROM boxcutter/python:3.11-slim-jammy

# Eclipse Temurin JRE 21 설치
RUN apt-get update && apt-get install -y \
    wget gnupg ca-certificates curl unzip \
 && mkdir -p /etc/apt/keyrings \
 && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb jammy main" > /etc/apt/sources.list.d/adoptium.list \
 && apt-get update \
 && apt-get install -y temurin-21-jre \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# Python 패키지 설치
RUN pip install --no-cache-dir numpy pandas simpy scipy isodate pyyaml dataclasses_json psycopg2-binary paho-mqtt python-dateutil opencv-python matplotlib PySide6 mdtpy

ENV MDT_HOME=/mdt

RUN mkdir -p /mdt/mdt-manager/config
RUN mkdir -p /mdt/mdt-manager/instances
RUN mkdir -p /mdt/mdt-manager/bundles

WORKDIR /mdt/mdt-manager
COPY mdt-manager-all.jar /mdt/mdt-manager/mdt-manager-all.jar
COPY mdt-instance-all.jar /mdt/mdt-manager/mdt-instance-all.jar

# ENTRYPOINT ["java", "-jar", "../mdt-manager-all.jar"]
CMD ["java", "-jar", "mdt-manager-all.jar"]
