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
# VARIANT could be one of four custer variants: open, strict, permissive and disabled
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
  INSTALLER_URL="https://downloads.dcos.io/dcos/${CHANNEL}/dcos_generate_config.sh"
else
  if [ -z "$DCOS_INSTALLER_TEMPLATE" ]; then
      echo "Expecting DCOS_INSTALLER_TEMPLATE environment variable"
      exit 1
  fi
  if [ -z "$DCOS_LICENSE" ]; then
      echo "Expecting $DCOS_LICENSE environment variable"
      exit 1
  fi
  # Replace {} in the template with the channel
  INSTALLER_URL=$(echo ${DCOS_INSTALLER_TEMPLATE} | sed "s|{}|${CHANNEL}|g")
fi

echo "Using: ${TEMPLATE}"

# Create config.yaml for dcos-launch.
envsubst <<EOF > "$CONFIG_PATH"
---
launch_config_version: 1
deployment_name: $DEPLOYMENT_NAME
platform: aws
instance_type: m4.large
os_name: cent-os-7-dcos-prereqs
aws_region: us-west-2
provider: onprem
installer_url: $INSTALLER_URL
ssh_user: centos
key_helper: true
num_masters: 3
fault_domain_helper:
  LocalRegion:
    num_zones: 2
    num_public_agents: 1
    num_private_agents: 2
    local: true
  RemoteRegion:
    num_zones: 2
    num_private_agents: 2
dcos_config:
  dns_search: us-west-2.compute.internal
  cluster_name: Marathon $DEPLOYMENT_NAME
  exhibitor_storage_backend: zookeeper
  exhibitor_zk_path: /exhibitor
  resolvers:
    - 8.8.8.8
    - 8.8.4.4
  master_discovery: static
EOF

# Append license if one is available.
if [ "$VARIANT" != "open" ]; then
    echo -e "  security: ${VARIANT}\n  license_key_contents: ${DCOS_LICENSE}" >> "$CONFIG_PATH"
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
