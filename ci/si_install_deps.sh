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

# Ensure amm is available.
if ! command -v amm >/dev/null 2>&1; then
    sudo curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/1.1.0/2.12-1.1.0 && \
    sudo chmod +x /usr/local/bin/amm && \
    echo "Ammonite successfully installed"
fi

# Install dcos-launch and test dependencies.
make init
