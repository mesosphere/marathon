#!/bin/bash
set -e

if [[ -z $WORKSPACE ]]; then
  WORKSPACE=$(realpath $(dirname $0)/../..)
  TARGETS_DIR=$WORKSPACE/targets-docker
else
  TARGET=$WORKSPACE/target
fi

TAGGED_IMAGE=$($WORKSPACE/bin/build-base-image.sh)
DOCKER_OPTIONS="run --entrypoint=/bin/bash --rm --name marathon-itests-$BUILD_ID --net host --privileged -e TARGETS_DIR=$TARGETS_DIR -e RUN_DOCKER_INTEGRATION_TESTS=$RUN_DOCKER_INTEGRATION_TESTS -e RUN_MESOS_INTEGRATION_TESTS=$RUN_MESOS_INTEGRATION_TESTS -e MARATHON_MAX_TASKS_PER_OFFER=$MARATHON_MAX_TASKS_PER_OFFER -v $WORKSPACE:/marathon -v $TARGET:/marathon/target -v /var/run/docker.sock:/var/run/docker.sock -v /etc/hosts:/etc/hosts -i"

DOCKER_CMD="/usr/local/bin/sbt -Dsbt.log.format=false unstable:test"

DOCKER_ARGS="$DOCKER_OPTIONS $TAGGED_IMAGE $DOCKER_CMD"

echo "Running docker $DOCKER_ARGS"

docker $DOCKER_ARGS 2>&1
