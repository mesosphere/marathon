#!/bin/bash
set -x +e -o pipefail

# Two parameters are expected: CHANNEL and VARIANT where CHANNEL is the respective PR and
# VARIANT could be one of four custer variants: open, strict, permissive and disabled
if [ "$#" -ne 2 ]; then
    echo "Expected 2 parameters: <channel> and <variant> e.g. si.sh testing/pull/1739 open"
    exit 1
fi

CHANNEL="$1"
VARIANT="$2"

JOB_NAME_SANITIZED=$(echo "$JOB_NAME" | tr -c '[:alnum:]-' '-')
DEPLOYMENT_NAME="$JOB_NAME_SANITIZED-$BUILD_NUMBER"
INFO_PATH="$DEPLOYMENT_NAME.info.json"

# Change work directory to ./tests
cd tests || exit 1

function create-junit-xml {
    local testsuite_name=$1
    local testcase_name=$2
    local error_message=$3

	cat > ../shakedown.xml <<-EOF
	<testsuites>
	  <testsuite name="$testsuite_name" errors="0" skipped="0" tests="1" failures="1">
	      <testcase classname="$testsuite_name" name="$testcase_name">
	        <failure message="test setup failed">$error_message</failure>
	      </testcase>
	  </testsuite>
	</testsuites>
	EOF
}

function exit-as-unstable {
    echo "$1"
    create-junit-xml "dcos-launch" "cluster.create" "$1"
    pipenv run dcos-launch -i "$INFO_PATH" delete
    ./ci/dataDogClient.sc "marathon.build.si.$VARIANT.failure" 1
    exit 0
}

function download-diagnostics-bundle {
	BUNDLE_NAME="$(dcos node diagnostics create all | grep -oE 'bundle-.*')"
	echo "Waiting for bundle ${BUNDLE_NAME} to be downloaded"
	STATUS_OUTPUT="$(dcos node diagnostics --status)"
	while [[ $STATUS_OUTPUT =~ "is_running: True" ]]; do
		echo "Diagnostics job still running, retrying in 5 seconds."
		sleep 5
		STATUS_OUTPUT="$(dcos node diagnostics --status)"
	done
	dcos node diagnostics download "${BUNDLE_NAME}" --location=./diagnostics.zip
}

# Install dependencies and expose new PATH value.
source "../ci/si_install_deps.sh"

# Launch cluster and run tests if launch was successful.
CLI_TEST_SSH_KEY="$(pwd)/$DEPLOYMENT_NAME.pem"
export CLI_TEST_SSH_KEY

if [ "$VARIANT" == "strict" ]; then
  DCOS_URL="https://$( ../ci/launch_cluster.sh "$CHANNEL" "$VARIANT" "$DEPLOYMENT_NAME" | tail -1 )"
  wget --no-check-certificate -O system/fixtures/dcos-ca.crt "$DCOS_URL/ca/dcos-ca.crt"
else
  DCOS_URL="http://$( ../ci/launch_cluster.sh "$CHANNEL" "$VARIANT" "$DEPLOYMENT_NAME" | tail -1 )"
fi

CLUSTER_LAUNCH_CODE=$?
export DCOS_URL
case $CLUSTER_LAUNCH_CODE in
  0)
      cp -f "$DOT_SHAKEDOWN" "$HOME/.shakedown"
      make test
      SI_CODE=$?
      if [ ${SI_CODE} -gt 0 ]; then
        ./ci/dataDogClient.sc "marathon.build.si.$VARIANT.failure" 1
        download-diagnostics-bundle
      else
        ./ci/dataDogClient.sc "marathon.build.si.$VARIANT.success" 1
      fi
      pipenv run dcos-launch -i "$INFO_PATH" delete || true
      exit "$SI_CODE" # Propagate return code.
      ;;
  2) exit-as-unstable "Cluster launch failed.";;
  3) exit-as-unstable "Cluster did not start in time.";;
  *) exit-as-unstable "Unknown error in cluster launch: $CLUSTER_LAUNCH_CODE";;
esac
