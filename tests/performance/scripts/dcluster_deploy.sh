#!/bin/bash
################################################################################
# [Fragment] Marathon in Development (Local) Cluster
# ------------------------------------------------------------------------------
# This script launches a local marathon development cluster for use by the later
# run script.
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

# Launch a cluster (we use `eval` to expand $DCLUSTER_ARGS)
eval marathon-dcluster \
  --detach $CLUSTER_CONFIG \
  --marathon $MARATHON_VERSION \
  --marathon_image $MARATHON_IMAGE \
  $DCLUSTER_ARGS
