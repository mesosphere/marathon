#!/bin/bash
set -e

if [[ -z $WORKSPACE ]]; then
  WORKSPACE=$(realpath $(dirname $0)/../..)
  TARGETS_DIR=$WORKSPACE/targets-docker
else
  TARGET=$WORKSPACE/target
fi

export DOCKER_HUB_USERNAME
export DOCKER_HUB_PASSWORD
export DOCKER_HUB_EMAIL
TAGGED_IMAGE=$($WORKSPACE/bin/build-base-image.sh)

if [[ -z ${RUN_DOCKER_INTEGRATION_TESTS+x} ]]; then
  if [[ -n "$JENKINS_HOME"]]; then
    # velocity can't run these tests (docker in docker in docker)
    RUN_DOCKER_INTEGRATION_TESTS="false"
  else
    if [[ -z ${DOCKER_MACHINE_NAME+x} ]]; then
      RUN_DOCKER_INTEGRATION_TESTS="true"
    else
      RUN_DOCKER_INTEGRATION_TESTS="false"
    fi
  fi
fi

export MARATHON_MAX_TASKS_PER_OFFER="${MARATHON_MAX_TASKS_PER_OFFER-1}"


DOCKER_OPTIONS="run --entrypoint=/bin/bash --rm --name marathon-itests-$BUILD_ID --net host --privileged -e TARGETS_DIR=$TARGETS_DIR -e RUN_DOCKER_INTEGRATION_TESTS=$RUN_DOCKER_INTEGRATION_TESTS -e MARATHON_MAX_TASKS_PER_OFFER=$MARATHON_MAX_TASKS_PER_OFFER -v $WORKSPACE:/marathon -v $TARGET:/marathon/target -v /var/run/docker.sock:/var/run/docker.sock -v /etc/hosts:/etc/hosts -i"

DOCKER_CMD="/usr/local/bin/sbt -Dsbt.log.format=false coverage doc test integration:test coverageReport coveralls mesos-simulation/integration:test mesos-simulation/test:runMain mesosphere.mesos.scale.DisplayAppScalingResults"

DOCKER_ARGS="$DOCKER_OPTIONS $TAGGED_IMAGE $DOCKER_CMD"

echo "Running docker $DOCKER_ARGS"

docker $DOCKER_ARGS 2>&1
