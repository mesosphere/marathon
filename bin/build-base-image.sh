#!/bin/bash

set -e

cd $(dirname $0)/..

BASE_IMAGE_NAME=mesosphere/marathon-build-base
BUILD_BASE_ID=$(md5sum Dockerfile.build *.sbt project/*.properties project/*.sbt project/*.scala | md5sum - | cut -f 1 -d ' ')
TAGGED_IMAGE=$BASE_IMAGE_NAME:$BUILD_BASE_ID

# Check if we need to build the base image
if [[ "$(docker images -q $TAGGED_IMAGE 2>/dev/null)" = "" ]]; then
  # try to pull, if that works, we're all set
  if [[ "$DOCKER_HUB_USERNAME" != "" ]]; then
    docker login -u "$DOCKER_HUB_USERNAME" -p "$DOCKER_HUB_PASSWORD" -e "$DOCKER_HUB_PASSWORD" >&2 2>/dev/null || true
  fi
  if ! docker pull $TAGGED_IMAGE 1>&2; then
    docker build -t $TAGGED_IMAGE -f Dockerfile.build . >&2
    if [[ "$DOCKER_HUB_USERNAME" != "" ]]; then
      docker push $TAGGED_IMAGE >&2 || true
    fi
  fi
  echo "Pulled $TAGGED_IMAGE" >&2
fi

echo $TAGGED_IMAGE
