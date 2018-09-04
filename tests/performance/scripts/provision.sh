#!/bin/bash
################################################################################
# [Fragment] Marathon in Development (Local) Cluster
# ------------------------------------------------------------------------------
# This script installs missing dependencies or bails early if something is not
# available in the user's computer.
################################################################################

# Check for missing dependencies that we cannot install
which python3 pip3 >/dev/null
if [ $? -ne 0 ]; then
  echo "ERROR: pip3 and python3 must be available in your system"
  exit 255
fi
which docker docker-compose >/dev/null
if [ $? -ne 0 ]; then
  echo "ERROR: docker and docker-compose must be available in your system"
  exit 255
fi

# Check for dependencies that we can install
which dcos-perf-test-driver >/dev/null
if [ $? -ne 0 ]; then
  echo "INFO: dcos-perf-test-driver was not found in your system, installing..."

  # Make workdir a python virtual env
  python3 -m venv $WORKDIR

  # Install perf driver
  (source $WORKDIR/bin/activate; pip3 install git+https://github.com/mesosphere/dcos-perf-test-driver)

  which dcos-perf-test-driver >/dev/null
  if [ $? -ne 0 ]; then
    echo "ERROR: Failed to install dcos-perf-test-driver"
    exit 254
  fi
fi
