#! /bin/bash

docker image rmi kwlee0220/mdt-manager

cp ../build/libs/mdt-manager-1.0.0-all.jar mdt-manager-all.jar

docker build -t kwlee0220/mdt-manager:latest .
# docker push kwlee0220/mdt-client:latest

rm mdt-manager-all.jar
