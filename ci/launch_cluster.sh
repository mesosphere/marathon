#!/bin/bash

set -x -e -o pipefail

# Two parameters are expected: CHANNEL and VARIANT where CHANNEL is the respective PR and
# VARIANT could be one of four custer variants: open, strict, permissive and disabled
if [ "$#" -ne 2 ]; then
    echo "Expected 2 parameters: <channel> and <variant> e.g. launch_cluster.sh testing/pull/1739 open"
    exit 1
fi

CHANNEL="$1"
VARIANT="$2"

if [ "$VARIANT" == "open" ]; then
  TEMPLATE="https://s3.amazonaws.com/downloads.dcos.io/dcos/${CHANNEL}/cloudformation/multi-master.cloudformation.json"
else
  TEMPLATE="https://s3.amazonaws.com/downloads.mesosphere.io/dcos-enterprise-aws-advanced/${CHANNEL}/${VARIANT}/cloudformation/ee.multi-master.cloudformation.json"
fi

echo "Workspace: ${WORKSPACE}"
echo "Using: ${TEMPLATE}"

apt-get update && apt-get install -y -t jessie-backports gettext-base wget
wget 'https://downloads.dcos.io/dcos-test-utils/bin/linux/dcos-launch' && chmod +x dcos-launch


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
    SlaveInstanceCount: 5
    LicenseKey: $DCOS_LICENSE
EOF

./dcos-launch create -L debug
if [ $? -ne 0 ]; then
  echo "Failed to launch a cluster via dcos-launch"
  exit 2
fi
if ! ./dcos-launch wait; then
  exit 3
fi

# Extract SSH key
jq -r .ssh_private_key cluster_info.json > "$CLI_TEST_SSH_KEY"

# Return dcos_url
echo "$(./dcos-launch describe | jq -r ".masters[0].public_ip")"
