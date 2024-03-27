#!/bin/bash

args=$#

if [ $args -le 0 ];then
    echo "version number required eg. 0.0.5"
    exit 1
fi

echo "Synchronising with master on bitbucket"

git pull -r   

# Set the version for the build and deploy
export version=$1

git tag "v$version"

echo "Building and Deploying to HealthConnect admin Admin Gateway version $version"

name=br-admin-gateway
folder=`pwd`

if [[ "$folder" != *"$name"* ]]; then
  folder=$folder/$name
fi

echo "$folder"
cd $folder


echo "building..."
./mvnw -ntp -Pprod clean verify jib:dockerBuild -DskipTests
echo "done."

echo "tagging..."
docker tag healthconnect-admin-gateway docker-registry.jojoaddison.net/healthconnect-admin-gateway:$version
docker image ls | grep 'healthconnect-admin-gateway'
echo "done."

echo "pushing..."
docker push docker-registry.jojoaddison.net/healthconnect-admin-gateway:$version
echo "done."
echo "build and deploy completed."
