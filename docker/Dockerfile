FROM eclipse-temurin:21.0.7_6-jdk

ENV MDT_HOME=/mdt

RUN mkdir -p /mdt/mdt-manager
RUN mkdir -p /mdt/share

WORKDIR /mdt/mdt-manager
COPY mdt-manager-all.jar /mdt/mdt-manager/mdt-manager-all.jar
COPY mdt-instance-all.jar /mdt/mdt-manager/mdt-instance-all.jar
COPY application.yml /mdt/mdt-manager/application.yml
COPY logback.xml /mdt/mdt-manager/logback.xml

# ENTRYPOINT ["java", "-jar", "../mdt-manager-all.jar"]
CMD ["java", "-jar", "mdt-manager-all.jar"]
