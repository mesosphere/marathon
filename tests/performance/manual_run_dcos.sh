#!/bin/bash
# This script runs the scale tests on a DC/OS cluster specified by the user

# Require a cluster URL
[ -z "$1" ] && echo -e "Usage: $0 <cluster> [<test file> ...]" && exit 1
CLUSTER_URL=$1
shift

# We need the CLI, so ask the user
if ! which dcos >/dev/null; then
  echo "ERROR: Please install the DC/OS CLI before using this tool"
  exit 1
fi

# Use DC/OS CLI to login if no authentication token was given
if [ -z "$DCOS_AUTH_TOKEN" ]; then

  # If DC/OS CLI is already configured to this cluster skip the setup step
  if [ "$CLUSTER_URL" != "$(dcos config show core.dcos_url)" ]; then
    dcos cluster setup "$CLUSTER_URL"
  fi

  DCOS_AUTH_TOKEN=$(dcos config show core.dcos_acs_token)
  [ -z "$DCOS_AUTH_TOKEN" ] \
    && echo "ERROR: Unable to obtain a DC/OS authentication token. Did you log-in?" \
    && exit 1
fi

# Collect some useful metadata
DCOS_VERSION=$(dcos --version | grep dcos.version | awk -F'=' '{print $2}')
MARATHON_VERSION=$(dcos marathon --version | awk '{print $3}')

# If the user hasn't specified any test files, run them all
TESTS=$*
[ -z "$TESTS" ] && TESTS=$(ls config/perf-driver/test-*.yml)
for TEST in $TESTS; do
  dcos-perf-test-driver \
    ./config/perf-driver/environments/target-cluster-custom.yml \
    ./config/perf-driver/environments/env-local.yml \
    "./$TEST" \
    -M "version=${DCOS_VERSION}" \
    -M "marathon=${MARATHON_VERSION}" \
    -D "dcos_auth_token=${DCOS_AUTH_TOKEN}" \
    -D "base_url=${CLUSTER_URL}"
done
