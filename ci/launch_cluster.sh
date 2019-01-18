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
if ! command -v terraform >/dev/null 2>&1; then
    echo "terraform was not found. Please install it."
    exit 1
fi

# Two parameters are expected: CHANNEL and VARIANT where CHANNEL is the respective PR and
# VARIANT could be one of three custer variants: open, strict or permissive.
if [ "$#" -ne 4 ]; then
    echo "Expected 4 parameters: launch_cluster.sh <channel> <variant> <deployment-name> <terraform-state>"
    echo "e.g. SHAKEDOWN_SSH_KEY_FILE='test.pem' launch_cluster.sh 'testing/pull/1739' 'open' 'si-testing-open'"
    exit 1
fi

CHANNEL="$1"
VARIANT="$2"
DEPLOYMENT_NAME="$3"
TERRAFORM_STATE="$4"

if [ "$VARIANT" == "open" ]; then
  INSTALLER="https://downloads.dcos.io/dcos/${CHANNEL}/dcos_generate_config.sh"
else
  INSTALLER="https://downloads.mesosphere.com/dcos-enterprise/${CHANNEL}/dcos_generate_config.ee.sh"
fi

echo "Using: ${INSTALLER}"

# Create cluster.
export AWS_DEFAULT_REGION="us-west-2"
terraform init
terraform apply -auto-approve -state "$TERRAFORM_STATE" \
	-var "cluster_name=\"$DEPLOYMENT_NAME\"" \
        -var "admin_ips=[\"$(curl http://whatismyip.akamai.com)/32\"]" \
	-var "dcos_variant=\"$VARIANT\"" \
        -var "ssh_public_key=\"$(ssh-add -L | head -n1)\"" \

terraform destroy -auto-approve -state "$TERRAFORM_STATE" \
	-var "cluster_name=\"$DEPLOYMENT_NAME\"" \
        -var "admin_ips=[\"$(curl http://whatismyip.akamai.com)/32\"]" \
	-var "dcos_variant=\"$VARIANT\"" \
        -var "ssh_public_key=\"$(ssh-add -L | head -n1)\""

# Append license and security mode for EE variants.
# if [ "$VARIANT" != "open" ]; then
#	dcos_security = "$VARIANT"
#	dcos_license_key_contents = "$DCOS_LICENSE"
#fi

if ! $?; then
  echo "Failed to launch a cluster via terraform"
  exit 2
fi

# Return dcos_url
# TODO(karsten): Use terraform output masters-loadbalancer instead.
CLUSTER_IP="$(jq '.modules[0].resources."aws_elb.public-master-elb".primary.attributes.dns_name' terraform.tfstate)"
echo "Launched cluster with IP $CLUSTER_IP"
echo "$CLUSTER_IP"
