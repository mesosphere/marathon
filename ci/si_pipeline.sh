#!/bin/bash
set -x +e -o pipefail

# Two parameters are expected: CHANNEL and VARIANT where CHANNEL is the respective PR and
# VARIANT could be one of three custer variants: open, strict or permissive.
if [ "$#" -ne 2 ]; then
    echo "Expected 2 parameters: <channel> and <variant> e.g. si.sh testing/pull/1739 open"
    exit 1
fi

CHANNEL="$1"
VARIANT="$2"

# JOB_NAME_SANITIZED=$(echo "$JOB_NAME" | tr -c '[:alnum:]-' '-')
JOB_NAME_SANITIZED="pr"
DEPLOYMENT_NAME="$JOB_NAME_SANITIZED-$BUILD_NUMBER"
ROOT_PATH=$(pwd)
TERRAFORM_STATE="$ROOT_PATH/$DEPLOYMENT_NAME.tfstate"

# Change work directory to ./tests
cd tests/system || exit 1

function create-junit-xml {
    local testsuite_name=$1
    local testcase_name=$2
    local error_message=$3

	cat > "$ROOT_PATH/shakedown.xml" <<-EOF
	<testsuites>
	  <testsuite name="$testsuite_name" errors="0" skipped="0" tests="1" failures="1">
	      <testcase classname="$testsuite_name" name="$testcase_name">
	        <failure message="test setup failed">$error_message</failure>
	      </testcase>
	  </testsuite>
	</testsuites>
	EOF
}

function exit-with-cluster-launch-error {
    echo "$1"
    create-junit-xml "dcos-launch" "cluster.create" "$1"
    terraform destroy -auto-approve -state "$TERRAFORM_STATE"
    "$ROOT_PATH/ci/dataDogClient.sc" "marathon.build.$JOB_NAME_SANITIZED.cluster_launch.failure" 1
    exit 0
}

function download-diagnostics-bundle {
	BUNDLE_NAME="$(pipenv run dcos node diagnostics create all | grep -oE 'bundle-.*')"
	echo "Waiting for bundle ${BUNDLE_NAME} to be downloaded"
	STATUS_OUTPUT="$(pipenv run dcos node diagnostics --status)"
	while [[ $STATUS_OUTPUT =~ "is_running: True" ]]; do
		echo "Diagnostics job still running, retrying in 5 seconds."
		sleep 5
		STATUS_OUTPUT="$(pipenv run dcos node diagnostics --status)"
	done
	pipenv run dcos node diagnostics download "${BUNDLE_NAME}" --location=./diagnostics.zip
}

# Install dependencies and expose new PATH value.
# shellcheck source=../../ci/si_install_deps.sh
source "$ROOT_PATH/ci/si_install_deps.sh"

# Launch cluster and run tests if launch was successful.
SHAKEDOWN_SSH_KEY_FILE="$(pwd)/$DEPLOYMENT_NAME"
export SHAKEDOWN_SSH_KEY_FILE

SHAKEDOWN_SSH_USER="centos"
export SHAKEDOWN_SSH_USER


# Configure cluster.
if [ "$VARIANT" == "open" ]; then
  INSTALLER="https://downloads.dcos.io/dcos/${CHANNEL}/dcos_generate_config.sh"
else
  INSTALLER="https://downloads.mesosphere.com/dcos-enterprise/${CHANNEL}/dcos_generate_config.ee.sh"
fi
echo "Using: ${INSTALLER}"

export AWS_DEFAULT_REGION="us-west-2"
export TF_VAR_cluster_name="$DEPLOYMENT_NAME"
export TF_VAR_admin_ips="[\"$(curl http://whatismyip.akamai.com)/32\"]"
export TF_VAR_dcos_variant="$VARIANT"
export TF_VAR_ssh_public_key="$(ssh-add -L | head -n1)"
export TF_VAR_dcos_installer="$INSTALLER"
# Append license and security mode for EE variants.
# if [ "$VARIANT" != "open" ]; then
#	dcos_security = "$VARIANT"
#	dcos_license_key_contents = "$DCOS_LICENSE"
#fi

# Create cluster.
terraform init -upgrade
terraform apply -auto-approve -state "$TERRAFORM_STATE"
CLUSTER_LAUNCH_CODE=$?
DCOS_URL="http://$(terraform output -state "$TERRAFORM_STATE" cluster_address)"
export DCOS_URL

if [ "$VARIANT" == "strict" ]; then
  DCOS_SSL_VERIFY="fixtures/dcos-ca.crt"
  wget --no-check-certificate -O "$DCOS_SSL_VERIFY" "$DCOS_URL/ca/dcos-ca.crt"
  export DCOS_SSL_VERIFY
else
  DCOS_SSL_VERIFY="false"
  export DCOS_SSL_VERIFY
fi

# Run tests.
case $CLUSTER_LAUNCH_CODE in
  0)
      "$ROOT_PATH/ci/dataDogClient.sc" "marathon.build.$JOB_NAME_SANITIZED.cluster_launch.success" 1
      cp -f "$DOT_SHAKEDOWN" "$HOME/.shakedown"
      timeout --preserve-status -s KILL 2h make test
      SI_CODE=$?
      if [ ${SI_CODE} -gt 0 ]; then
        "$ROOT_PATH/ci/dataDogClient.sc" "marathon.build.$JOB_NAME_SANITIZED.failure" 1
        download-diagnostics-bundle
      else
        "$ROOT_PATH/ci/dataDogClient.sc" "marathon.build.$JOB_NAME_SANITIZED.success" 1
      fi
      terraform destroy -auto-approve -state "$TERRAFORM_STATE" || true
      exit "$SI_CODE" # Propagate return code.
      ;;
  1) exit-with-cluster-launch-error "Dependencies are missing.";;
  2) exit-with-cluster-launch-error "Cluster launch failed.";;
  3) exit-with-cluster-launch-error "Cluster did not start in time.";;
  *) exit-with-cluster-launch-error "Unknown error in cluster launch: $CLUSTER_LAUNCH_CODE";;
esac
