#!/bin/bash
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

  # Build docker image and keep track of the build log
  # in order to extract the marathon image at the end.
  sbt docker:publishLocal | tee build.log
)
RET=$?; [ $RET -ne 0 ] && exit $RET

# Get the docker image name from the logs
MARATHON_VERSION_EXPR=$(tail $MARATHON_DIR/build.log  | grep 'Built image' | awk '{print $4}' | sed $'s,\x1b\\[[0-9;]*[a-zA-Z],,g')

# Export marathon image and version for other scripts
export MARATHON_IMAGE=$(echo $MARATHON_VERSION_EXPR | awk -F':' '{print $1}')
export MARATHON_VERSION=$(echo $MARATHON_VERSION_EXPR | awk -F':' '{print $2}')
