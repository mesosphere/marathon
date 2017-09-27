#!/bin/bash
################################################################################
# [Fragment] Marathon in Development (Local) Cluster
# ------------------------------------------------------------------------------
# This script tears down the local cluster created in the previous steps.
################################################################################

# Validate environment
if [ -z "$MARATHON_VERSION" ]; then
  echo "ERROR: Required 'MARATHON_VERSION' environment variable"
  exit 253
fi
if [ -z "$MARATHON_IMAGE" ]; then
  echo "ERROR: Required 'MARATHON_IMAGE' environment variable"
  exit 253
fi
if [ -z "$CLUSTER_CONFIG" ]; then
  echo "ERROR: Required 'CLUSTER_CONFIG' environment variable"
  exit 253
fi

# Launch a cluster
marathon-dcluster \
  --rm $CLUSTER_CONFIG \
  --marathon $MARATHON_VERSION \
  --marathon_image $MARATHON_IMAGE
