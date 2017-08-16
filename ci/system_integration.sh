#!/bin/bash

set -e -o pipefail

./ci/launch_cluster.sh

DCOS_URL="http://$(./dcos-launch describe | jq -r ".masters[0].public_ip")/"
cp -f "$DOT_SHAKEDOWN" "$HOME/.shakedown"

TERM=velocity shakedown \
  --stdout all \
  --stdout-inline \
  --timeout 360000 \
  --pytest-option "--junitxml=shakedown.xml" \
  --ssh-key-file "$CLI_TEST_SSH_KEY" \
  --dcos-url "$DCOS_URL" tests/system/test_marathon_root.py tests/system/test_marathon_universe.py

