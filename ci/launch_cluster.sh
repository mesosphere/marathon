#!/bin/bash

set -x -e -o pipefail

# Two parameters are expected: CHANNEL and VARIANT where CHANNEL is the respective PR and
# VARIANT could be one of four custer variants: open, strict, permissive and disabled
#
# Also, when non-open variants are selected, the following environment variables must
# be available: DCOS_INSTALLER_TEMPLATE and DCOS_LICENSE
#
if [ "$#" -ne 2 ]; then
    echo "Expected 2 parameters: <channel> and <variant> e.g. launch_cluster.sh testing/pull/1739 open"
    exit 1
fi

CHANNEL="$1"
VARIANT="$2"

if [ "$VARIANT" == "open" ]; then
  INSTALLER_URL="https://downloads.dcos.io/dcos/${CHANNEL}/dcos_generate_config.sh"
  EXTRA_DCOS_CONFIG=""
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
  INSTALLER_URL=${$DCOS_INSTALLER_TEMPLATE/{\}/$CHANNEL}
  EXTRA_DCOS_CONFIG="  security: ${VARIANT}"$'\n'"  license_key_contents: ${DCOS_LICENSE}"
fi

echo "Workspace: ${WORKSPACE}"
echo "Using: ${INSTALLER_URL}"

PLATFORM=`uname`
if [ "$PLATFORM" == 'Darwin' ]; then
    brew install gettext
else
    apt-get update && apt-get install -y -t jessie-backports gettext-base wget
fi
wget 'https://downloads.dcos.io/dcos-test-utils/bin/linux/dcos-launch' && chmod +x dcos-launch


envsubst <<EOF > config.yaml
---
launch_config_version: 1
deployment_name: $DEPLOYMENT_NAME
platform: aws
instance_type: m4.large
os_name: cent-os-7-dcos-prereqs
aws_region: us-west-2
provider: onprem
installer_url: $INSTALLER_URL
key_helper: true
num_masters: 3
fault_domain_helper:
  LocalZone:
    num_zones: 2
    num_public_agents: 1
    num_private_agents: 2
    local: true
  RemoteZone:
    num_zones: 2
    num_private_agents: 2
dcos_config:
  cluster_name: Marathon $DEPLOYMENT_NAME
  exhibitor_storage_backend: zookeeper
  exhibitor_zk_path: /exhibitor
  resolvers:
    - 8.8.8.8
    - 8.8.4.4
  master_discovery: static
$EXTRA_DCOS_CONFIG
EOF

./dcos-launch create
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
