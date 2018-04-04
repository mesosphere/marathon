#!/bin/bash
set -x +e -o pipefail

PLATFORM=$(uname)

# Ensure envsubst is available.
if ! command -v envsubst >/dev/null 2>&1; then
    if [ "$PLATFORM" == 'Darwin' ]; then
        echo "Installing gettext to get envsubst."
        brew install gettext
        PATH=$PATH:/usr/local/opt/gettext/bin/
        export PATH
    else
        apt-get update && apt-get install -y -t jessie-backports gettext-base wget
    fi
fi

# Install dcos-launch and test dependencies.
make init
