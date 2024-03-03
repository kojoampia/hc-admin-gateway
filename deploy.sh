#!/bin/bash

echo "tagging..."
docker tag admingateway docker-registry.jojoaddison.net/hc/admingateway:latest
docker image ls | grep 'admingateway'
echo "done."

echo "pushing..."
docker push docker-registry.jojoaddison.net/hc/admingateway:latest
echo "done."
echo "build and deploy completed."
