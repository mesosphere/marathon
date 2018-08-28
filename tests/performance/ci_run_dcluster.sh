#!/bin/bash
# This script runs the scale tests locally, by deploying a development cluster
# using docker-compose.

# Current directory
BASEDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Allow some parameters to be overwritten from the environment
[ -z "$MARATHON_DIR" ] && MARATHON_DIR=$(dirname "$(dirname "$BASEDIR")")
[ -z "$WORKDIR" ] && WORKDIR=$(pwd)
[ -z "$BUILD_NUMBER" ] && BUILD_NUMBER=$(date +%Y%m%d%H%M%S)

# Export some variables that are going to be used by the step scripts
export MARATHON_DIR=$MARATHON_DIR
export PATH=$PATH:$WORKDIR/bin
export WORKDIR=$WORKDIR

# Tuning parameters
export TESTS_DIR="$BASEDIR/config/perf-driver"

# Step 1) Install dependencies and build Marathon
# shellcheck source=./scripts/provision.sh
source "$BASEDIR/scripts/provision.sh"
RET=$?; [ $RET -ne 0 ] && exit $RET

# shellcheck source=./scripts/build.sh
source "$BASEDIR/scripts/build.sh"
RET=$?; [ $RET -ne 0 ] && exit $RET

# Step 2) Start cluster
CLUSTER_WORKDIR="$WORKDIR/$BUILD_NUMBER"
mkdir -p "$CLUSTER_WORKDIR"

# Docker Compose cluster configuration.
export MESOS_VERSION=1.5.1-rc1
export MARATHON_VERSION=$("$MARATHON_DIR/version" docker)
export CLUSTER_WORKDIR=$CLUSTER_WORKDIR
docker-compose -f config/docker-compose.yml up --scale mesos_agent=2 --detach

# Step 3) Run scale tests and carry the exit code
# shellcheck source=./scripts/run.sh
source "$BASEDIR/scripts/run.sh" "$@"
# ^ This script exposes the EXITCODE environment variable

# Step 4) Teardown cluster
docker-compose -f config/docker-compose.yml rm --force --stop
tar -zcf "marathon-performance-$BUILD_NUMBER.log.tar.gz" "$WORKDIR/$BUILD_NUMBER"

# Exit with the test's exit code
exit "$EXITCODE"
