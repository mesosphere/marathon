#!/bin/bash

set -e -o pipefail

# Hardcode configuration to open DC/OS.
VARIANT="open"
CHANNEL="testing/pull/1739"

JOB_NAME_SANITIZED=$(echo "$JOB_NAME" | tr -c '[:alnum:]-' '-')
DEPLOYMENT_NAME="$JOB_NAME_SANITIZED-$(date +%s)"

if [ "$VARIANT" == "open" ]; then
  TEMPLATE="https://s3.amazonaws.com/downloads.dcos.io/dcos/${CHANNEL}/cloudformation/single-master.cloudformation.json"
else
  TEMPLATE="https://s3.amazonaws.com/downloads.mesosphere.io/dcos-enterprise-aws-advanced/${CHANNEL}/${VARIANT}/cloudformation/ee.single-master.cloudformation.json"
fi

echo "Workspace: ${WORKSPACE}"
echo "Using: ${TEMPLATE}"

apk update
apk --upgrade add gettext wget
wget 'https://downloads.dcos.io/dcos-test-utils/bin/linux/dcos-launch' && chmod +x dcos-launch


envsubst <<EOF > config.yaml
---
launch_config_version: 1
template_url: $TEMPLATE
deployment_name: $DEPLOYMENT_NAME
provider: aws
aws_region: eu-central-1
template_parameters:
    KeyName: default
    AdminLocation: 0.0.0.0/0
    PublicSlaveInstanceCount: 1
    SlaveInstanceCount: 5
EOF
./dcos-launch create
./dcos-launch wait
