FROM eclipse-temurin:21.0.7_6-jdk

RUN mkdir -p /mdt/mdt-manager

ENV MDT_HOME=/mdt
ENV MDT_GLOBAL_CONFIG_FILE=/mdt/mdt-manager/mdt_global_config.json

WORKDIR /mdt/mdt-manager
ENTRYPOINT ["java", "-jar", "../mdt-manager-all.jar"]
