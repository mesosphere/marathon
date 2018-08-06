#!/bin/bash
# This script runs the scale tests on a DC/OS cluster specified by the user

# Require a cluster URL
[ -z "$1" ] && echo -e "Usage: $0 <marathon> [<test file> ...]" && exit 1
MARATHON_URL=$1
shift

# We need the CLI, so ask the user
if ! which dcos >/dev/null; then
  echo "ERROR: Please install the DC/OS CLI before using this tool"
  exit 1
fi
if ! which dcos-perf-test-driver >/dev/null; then
  echo "ERROR: Please install the DC/OS Performance Test Driver before using this tool"
  exit 1
fi

# Collect some useful metadata
DCOS_VERSION=$(dcos --version | grep dcos.version | awk -F'=' '{print $2}')
MARATHON_VERSION=$(dcos marathon --version | awk '{print $3}')

# If the user hasn't specified any test files, run them all
TESTS=$*
[ -z "$TESTS" ] && TESTS=$(ls config/perf-driver/test-*.yml)
for TEST in $TESTS; do
  dcos-perf-test-driver \
    ./config/perf-driver/environments/target-standalone.yml \
    ./config/perf-driver/environments/env-local.yml \
    "./$TEST" \
    -M "version=${DCOS_VERSION}" \
    -M "marathon=${MARATHON_VERSION}" \
    -D "dcos_auth_token=${DCOS_AUTH_TOKEN}" \
    -D "marathon_url=${MARATHON_URL}"
done
