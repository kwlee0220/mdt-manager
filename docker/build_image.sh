#! /bin/bash

MDT_MANAGER_HOME=$MDT_HOME/mdt-manager
VERSION=1.2.0

docker image rmi -f kwlee0220/mdt-manager:$VERSION

cp $MDT_MANAGER_HOME/mdt-manager-all.jar mdt-manager-all.jar
cp $MDT_MANAGER_HOME/mdt-instance-all.jar mdt-instance-all.jar

docker build -t kwlee0220/mdt-manager:$VERSION .
# docker push kwlee0220/mdt-client:latest

rm mdt-manager-all.jar
rm mdt-instance-all.jar
