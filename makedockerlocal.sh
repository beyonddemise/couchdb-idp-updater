#!/bin/bash
# This script is used to build a docker image from a local directory
mvn clean package -Dnative
docker build -f src/main/docker/Dockerfile.native-micro -t beyonddemise/couchdb-idp-updater .
docker run --rm -it -v "$(pwd)"/data:/work/data beyonddemise/couchdb-idp-updater