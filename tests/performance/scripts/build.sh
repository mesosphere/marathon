#!/bin/bash
set -x -e
################################################################################
# [Fragment] Marathon in Development (Local) Cluster
# ------------------------------------------------------------------------------
# This script builds a docker image with the current marathon and makes it
# available to the next steps.
################################################################################

# Validate environment
if [ -z "$MARATHON_DIR" ]; then
  echo "ERROR: Required `MARATHON_DIR` environment variable"
  exit 253
fi

(
  # Cleanup marathon
  cd $MARATHON_DIR;
  rm -rf target;
  sbt clean;

  sbt docker:publishLocal
)
