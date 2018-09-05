#!/bin/bash
set -x -e -o pipefail
# This script runs the scale tests locally, by deploying a development cluster
# using docker-compose.

# Current directory
BASEDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Allow some parameters to be overwritten from the environment
[ -z "$MARATHON_DIR" ] && MARATHON_DIR=$(dirname "$(dirname "$BASEDIR")")
[ -z "$WORKDIR" ] && WORKDIR=$(pwd)
[ -z "$BUILD_NUMBER" ] && BUILD_NUMBER=$(date +%Y%m%d%H%M%S)
[ -z "$DOCKER_NETWORK" ] && DOCKER_NETWORK=testing

# Export some variables that are going to be used by the step scripts
export MARATHON_DIR=$MARATHON_DIR
export PATH=$PATH:$WORKDIR/bin
export WORKDIR=$WORKDIR

# Tuning parameters
export TESTS_DIR="$BASEDIR/config/perf-driver"

# Step 1) Install dependencies and build Marathon
echo "$(date) Provision and build Marathon."
# shellcheck source=./scripts/provision.sh
source "$BASEDIR/scripts/provision.sh"
RET=$?; [ $RET -ne 0 ] && exit $RET

# shellcheck source=./scripts/build.sh
source "$BASEDIR/scripts/build.sh"
RET=$?; [ $RET -ne 0 ] && exit $RET

# Step 2) Start cluster
CLUSTER_WORKDIR="$WORKDIR/$BUILD_NUMBER"
mkdir -p "$CLUSTER_WORKDIR/log/mesos-master"
mkdir -p "$CLUSTER_WORKDIR/log/mesos-agent"
mkdir -p "$CLUSTER_WORKDIR/var/mesos-master"
mkdir -p "$CLUSTER_WORKDIR/var/mesos-agent"

# Docker Compose cluster configuration.
echo "$(date) Start cluster."
MARATHON_VERSION=$("$MARATHON_DIR/version" docker)

# Get full path to docker-compose file
DOCKER_COMPOSE_FILE="$BASEDIR/config/docker-compose.yml"

# Start docker-compose, detached
export MESOS_VERSION=1.5.1-rc1
export MARATHON_VERSION=$MARATHON_VERSION
export CLUSTER_WORKDIR=$CLUSTER_WORKDIR
export NETWORK_ID=$DOCKER_NETWORK
export MARATHON_PERF_TESTING_DIR=$MARATHON_PERF_TESTING_DIR
docker-compose -f "${DOCKER_COMPOSE_FILE}" up --scale mesos_agent=2 -d

# Register an exit handler that will tear down the cluster and collect logs
function cleanup_cluster {
  echo "Collecting cluster logs"
  docker-compose -f "${DOCKER_COMPOSE_FILE}" logs &> "$CLUSTER_WORKDIR/cluster.log"
  echo "Tearing down cluster"
  docker-compose -f "${DOCKER_COMPOSE_FILE}" rm --force --stop
  tar -zcf "benchmark-$BUILD_NUMBER.tar.gz" "$CLUSTER_WORKDIR"
  rm -rf "$CLUSTER_WORKDIR"
}
trap cleanup_cluster EXIT

# Step 3) Run scale tests and carry the exit code
echo "$(date) Run benchmarks."
# shellcheck source=./scripts/run.sh
source "$BASEDIR/scripts/run.sh" "$@"
# ^ This script exposes the EXITCODE environment variable

# Exit with the test's exit code
exit "$EXITCODE"
