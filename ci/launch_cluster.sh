#!/bin/bash
set -x -e -o pipefail

# Ensure dependencies are installed.
if ! command -v jq >/dev/null; then
    echo "jq was not found. Please install it."
    exit 1
fi
if ! command -v envsubst >/dev/null 2>&1; then
    echo "envsubst was not found. Please install along with gettext."
    exit 1
fi
if ! command -v dcos-launch >/dev/null 2>&1; then
    echo "dcos-launch was not found."
    echo "Please install it following the instructions at https://github.com/dcos/dcos-launch#installation."
    exit 1
fi

# Two parameters are expected: CHANNEL and VARIANT where CHANNEL is the respective PR and
# VARIANT could be one of four custer variants: open, strict, permissive and disabled
if [ "$#" -ne 3 ]; then
    echo "Expected 3 parameters: launch_cluster.sh <channel> <variant> <deployment-name>"
    echo "e.g. CLI_TEST_SSH_KEY='test.pem' launch_cluster.sh 'testing/pull/1739' 'open' 'si-testing-open'"
    exit 1
fi

CHANNEL="$1"
VARIANT="$2"
DEPLOYMENT_NAME="$3"

if [ "$VARIANT" == "open" ]; then
  TEMPLATE="https://s3.amazonaws.com/downloads.dcos.io/dcos/${CHANNEL}/cloudformation/multi-master.cloudformation.json"
else
  TEMPLATE="https://s3.amazonaws.com/downloads.mesosphere.io/dcos-enterprise-aws-advanced/${CHANNEL}/${VARIANT}/cloudformation/ee.multi-master.cloudformation.json"
fi

echo "Using: ${TEMPLATE}"


# Create config.yaml for dcos-launch.
envsubst <<EOF > config.yaml
---
launch_config_version: 1
template_url: $TEMPLATE
deployment_name: $DEPLOYMENT_NAME
provider: aws
aws_region: us-west-2
key_helper: true
template_parameters:
    DefaultInstanceType: m4.large
    AdminLocation: 0.0.0.0/0
    PublicSlaveInstanceCount: 1
    SlaveInstanceCount: 3
EOF

# Append license if one is available.
if "$VARIANT" -ne "open"; then
    echo "    LicenseKey: $DCOS_LICENSE" >> config.yaml
fi

# Create cluster.
if ! dcos-launch create; then
  echo "Failed to launch a cluster via dcos-launch"
  exit 2
fi
if ! dcos-launch wait; then
  exit 3
fi

# Extract SSH key
jq -r .ssh_private_key cluster_info.json > "$CLI_TEST_SSH_KEY"

# Return dcos_url
echo "$(dcos-launch describe | jq -r ".masters[0].public_ip")"
