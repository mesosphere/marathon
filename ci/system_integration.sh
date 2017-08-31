#!/bin/bash

set -e -o pipefail

if [ "$#" -ne 1 ]; then
    echo "Expected 1 parameter: <dcos_url> e.g. system_integration.sh http://..."
    exit 1
fi

DCOS_URL="$1"
cp -f "$DOT_SHAKEDOWN" "$HOME/.shakedown"

TERM=velocity shakedown \
  --stdout all \
  --stdout-inline \
  --ssl-no-verify \
  --timeout 360000 \
  --pytest-option "--junitxml=shakedown.xml" \
  --ssh-key-file "$CLI_TEST_SSH_KEY" \
  --dcos-url "$DCOS_URL" tests/system/test_marathon_root.py tests/system/test_marathon_universe.py

