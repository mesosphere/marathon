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

# Two parameters are expected: CHANNEL and VARIANT where CHANNEL is the respective PR and
# VARIANT could be one of three custer variants: open, strict or permissive.
if [ "$#" -ne 3 ]; then
    echo "Expected 3 parameters: launch_cluster.sh <channel> <variant> <deployment-name>"
    echo "e.g. CLI_TEST_SSH_KEY='test.pem' launch_cluster.sh 'testing/pull/1739' 'open' 'si-testing-open'"
    exit 1
fi

CHANNEL="$1"
VARIANT="$2"
DEPLOYMENT_NAME="$3"
CONFIG_PATH="$DEPLOYMENT_NAME.yaml"
INFO_PATH="$DEPLOYMENT_NAME.info.json"

if [ "$VARIANT" == "open" ]; then
  INSTALLER="https://downloads.dcos.io/dcos/${CHANNEL}/dcos_generate_config.sh"
else
  INSTALLER="https://downloads.mesosphere.com/dcos-enterprise-aws-advanced/${CHANNEL}/${VARIANT}/dcos_generate_config.sh"
fi

echo "Using: ${INSTALLER}"

# Create config.yaml for dcos-launch.
envsubst <<EOF > "$CONFIG_PATH"
---
launch_config_version: 1
deployment_name: $DEPLOYMENT_NAME
installer_url: $INSTALLER
provider: onprem
platform: aws
aws_region: us-west-2
os_name: cent-os-7-dcos-prereqs
key_helper: true
instance_type: m4.large
num_public_agents: 1
num_private_agents: 3
num_masters: 3
dcos_config:
  cluster_name: $DEPLOYMENT_NAME
  resolvers:
    - 8.8.4.4
    - 8.8.8.8
  dns_search: mesos
  master_discovery: static
  exhibitor_storage_backend: static
  rexray_config_preset: aws
EOF

# Append license if one is available.
if [ "$VARIANT" != "open" ]; then
    echo "    LicenseKey: $DCOS_LICENSE" >> "$CONFIG_PATH"
fi

# Create cluster.
if ! pipenv run dcos-launch -c "$CONFIG_PATH" -i "$INFO_PATH" create; then
  echo "Failed to launch a cluster via dcos-launch"
  exit 2
fi
if ! pipenv run dcos-launch -i "$INFO_PATH" wait; then
  exit 3
fi

# Extract SSH key
jq -r .ssh_private_key "$INFO_PATH" > "$CLI_TEST_SSH_KEY"

# Return dcos_url
CLUSTER_IP="$(pipenv run dcos-launch -i "$INFO_PATH" describe | jq -r ".masters[0].public_ip")"
echo "Launched cluster with IP $CLUSTER_IP"
echo "$CLUSTER_IP"
