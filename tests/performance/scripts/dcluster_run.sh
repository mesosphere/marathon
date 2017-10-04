#!/bin/bash
################################################################################
# [Fragment] Marathon in Development (Local) Cluster
# ------------------------------------------------------------------------------
# This script runs the scale tests on the cluster deployed in the previous steps
################################################################################

# Validate environment
if [ -z "$TESTS_DIR" ]; then
  echo "ERROR: Required 'TESTS_DIR' environment variable"
  exit 253
fi
if [ -z "$CLUSTER_CONFIG" ]; then
  echo "ERROR: Required 'CLUSTER_CONFIG' environment variable"
  exit 253
fi
if [ -z "$MARATHON_VERSION" ]; then
  echo "ERROR: Required 'MARATHON_VERSION' environment variable"
  exit 253
fi
if [ -z "$DATADOG_API_KEY" ]; then
  echo "ERROR: Required 'DATADOG_API_KEY' environment variable"
  exit 253
fi
if [ -z "$DATADOG_APP_KEY" ]; then
  echo "ERROR: Required 'DATADOG_APP_KEY' environment variable"
  exit 253
fi

# Get mesos version from the cluster config
MESOS_VERSION=$(cat $CLUSTER_CONFIG | grep 'mesos\s*=' | awk -F'=' '{print $2}' | tr -d ' ')

# Execute all the tests in the configuration
EXITCODE=0
for TEST_CONFIG in $TESTS_DIR/test-*.yml; do

  # Get test name by removing 'test-' prefix and '.yml' suffix
  TEST_NAME=$(basename $TEST_CONFIG)
  TRIM_END=${#TEST_NAME}
  let TRIM_END-=9
  TEST_NAME=${TEST_NAME:5:$TRIM_END}

  # If we have partial tests configured, check if this test exists
  # in the PARTIAL_TESTS, otherwise skip
  if [[ ! -z "$PARTIAL_TESTS" && ! "$PARTIAL_TESTS" =~ "$TEST_NAME" ]]; then
    echo "INFO: Skipping test '${TEST_NAME}' according to PARTIAL_TESTS env vaiable"
    continue
  fi
  echo "INFO: Executing test '${TEST_NAME}'"

  # Launch the performance test driver with the correct arguments
  eval dcos-perf-test-driver \
    $TESTS_DIR/environments/dcluster.yml \
    $TEST_CONFIG \
    -M "version=${MARATHON_VERSION}" \
    -M "mesos=${MESOS_VERSION}" \
    -D "jmx_port=9010" \
    -D "datadog_api_key=${DATADOG_API_KEY}" \
    -D "datadog_app_key=${DATADOG_APP_KEY}" \
    $*
  EXITCODE=$?; [ $EXITCODE -ne 0 ] && break

done

# Expose EXITCODE to make it available to other tasks
export EXITCODE=$EXITCODE
