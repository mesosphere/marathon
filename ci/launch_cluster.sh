#!/bin/bash

set -x -e -o pipefail

# Ensure jq is installed.
if ! command -v jq >/dev/null; then
    echo "jq was not found. Please install it."
fi

# Two parameters are expected: CHANNEL and VARIANT where CHANNEL is the respective PR and
# VARIANT could be one of four custer variants: open, strict, permissive and disabled
if [ "$#" -ne 3 ]; then
    echo "Expected 3 parameters: launch_cluster.sh <channel> <variant> <deployment-name>"
    echo "e.g. CLI_TEST_SSH_KEY="test.pem" launch_cluster.sh testing/pull/1739 open si-testing-open"
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

# Ensure envsubst is available.
if ! command -v envsubst >/dev/null 2>&1; then
    PLATFORM=$(uname)
    if [ "$PLATFORM" == 'Darwin' ]; then
        if [ ! -f /usr/local/opt/gettext/bin/envsubst ]; then
            echo "Installing gettext to get envsubst."
            brew install gettext
        fi
        PATH=$PATH:/usr/local/opt/gettext/bin/
    else
        apt-get update && apt-get install -y -t jessie-backports gettext-base wget
    fi
fi

# Ensure dcos-launch is available.
mkdir -p "$(pwd)/bin"
PATH=$PATH:"$(pwd)/bin"
if ! command -v dcos-launch >/dev/null 2>&1; then
    echo "dcos-launch was not found. Downloading '$(pwd)/bin' ..."
    wget 'https://downloads.dcos.io/dcos-test-utils/bin/linux/dcos-launch' \
        -P "$(pwd)/bin" && chmod +x "$(pwd)/bin/dcos-launch"
fi


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
if grep -q 'LicenseKey' "$(curl "$TEMPLATE")"; then
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
echo "$(./dcos-launch describe | jq -r ".masters[0].public_ip")"
