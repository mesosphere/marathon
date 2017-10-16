#!/bin/bash
# This script runs the scale tests locally, by deploying a development cluster
# using docker-compose.

# Current directory
BASEDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Allow some parameters to be overwritten from the environment
[ -z "$MARATHON_DIR" ] && MARATHON_DIR=$(dirname $(dirname $BASEDIR))
[ -z "$WORKDIR" ] && WORKDIR=$(pwd)

# Export some variables that are going to be used by the step scripts
export CONFIG_DIR=$BASEDIR/config
export MARATHON_DIR=$MARATHON_DIR
export PATH=$PATH:$WORKDIR/bin
export WORKDIR=$WORKDIR

# Tuning parameters
export CLUSTER_CONFIG=$CONFIG_DIR/dcluster/large-cluster.conf
export TESTS_DIR=$CONFIG_DIR/perf-driver

# Step 1) Install dependencies
source $BASEDIR/scripts/dcluster_install.sh
RET=$?; [ $RET -ne 0 ] && exit $RET

# Step 2) Build marathon
source $BASEDIR/scripts/dcluster_build.sh
RET=$?; [ $RET -ne 0 ] && exit $RET

# Step 3) Deploy cluster
source $BASEDIR/scripts/dcluster_deploy.sh
RET=$?; [ $RET -ne 0 ] && exit $RET

# Step 4) Run scale tests and carry the exit code
source $BASEDIR/scripts/dcluster_run.sh $*
# ^ This script exposes the EXITCODE environment variable

# Step 5) Teardown cluster
source $BASEDIR/scripts/dcluster_teardown.sh
RET=$?; [ $RET -ne 0 ] && exit $RET

# Exit with the test's exit code
exit $EXITCODE
