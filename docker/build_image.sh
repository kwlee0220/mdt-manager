#! /bin/bash

MDT_MANAGER_HOME=$MDT_HOME/mdt-manager
VERSION=1.2.0

docker image rmi -f kwlee0220/mdt-manager:$VERSION

cp $MDT_MANAGER_HOME/mdt-manager-all.jar mdt-manager-all.jar
cp $MDT_MANAGER_HOME/mdt-instance-all.jar mdt-instance-all.jar
cp $MDT_MANAGER_HOME/application.yml application.yml
cp $MDT_MANAGER_HOME/logback.xml logback.xml

docker build -t kwlee0220/mdt-manager:$VERSION .
# docker push kwlee0220/mdt-client:latest

rm mdt-manager-all.jar
rm application.yml
rm logback.xml
