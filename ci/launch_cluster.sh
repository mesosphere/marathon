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
TERRAFORM_VARS="$DEPLOYMENT_NAME.tfvars"
TERRAFORM_STATE="$4"

if [ "$VARIANT" == "open" ]; then
  INSTALLER="https://downloads.dcos.io/dcos/${CHANNEL}/dcos_generate_config.sh"
  TERRAFORM_MODULE="git@github.com/dcos/terraform-dcos/aws"
else
  INSTALLER="https://downloads.mesosphere.com/dcos-enterprise/${CHANNEL}/dcos_generate_config.ee.sh"
  TERRAFORM_MODULE="git@github.com:mesosphere/enterprise-terraform-dcos/aws"
fi

echo "Using: ${INSTALLER}"

rm -rf "terraform-plan"
mkdir "terraform-plan"
terraform init -from-module "$TERRAFORM_MODULE" "terraform-plan"

# Create .tfvars file for terraform.
envsubst <<EOF > "$TERRAFORM_VARS"
dcos_cluster_name = "$DEPLOYMENT_NAME"
custom_dcos_download_path = "$INSTALLER"
num_of_masters = "3"
num_of_private_agents = "3"
num_of_public_agents = "1"
aws_region = "us-west-2"
aws_bootstrap_instance_type = "m4.large"
aws_master_instance_type = "m4.large"
aws_agent_instance_type = "m4.large"
aws_public_agent_instance_type = "m4.large"
os = "centos_7.2"
ssh_key_name = "default"
# Inbound Master Access
admin_cidr = "0.0.0.0/0"
EOF

# Append license and security mode for EE variants.
if [ "$VARIANT" != "open" ]; then
	dcos_security = "$VARIANT"
	dcos_license_key_contents = "$DCOS_LICENSE"
fi

# Create cluster.
if ! terraform apply -auto-approve -var-file "$TERRAFORM_VARS" -state "$TERRAFORM_STATE" "terraform-plan"; then
  echo "Failed to launch a cluster via terraform"
  exit 2
fi

# Extract SSH key
# TODO(karsten): jq -r .ssh_private_key "$INFO_PATH" > "$SHAKEDOWN_SSH_KEY_FILE"

# Return dcos_url
CLUSTER_IP="$(jq '.modules[0].resources."aws_elb.public-master-elb".primary.attributes.dns_name' terraform.tfstate)"
echo "Launched cluster with IP $CLUSTER_IP"
echo "$CLUSTER_IP"
