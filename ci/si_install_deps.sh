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

# Ensure dcos-launch is available.
mkdir -p "$(pwd)/bin"
PATH="$PATH:$(pwd)/bin"
if ! command -v dcos-launch >/dev/null 2>&1; then
    echo "dcos-launch was not found. Downloading '$(pwd)/bin' ..."
    if [ "$PLATFORM" == 'Darwin' ]; then
        URL='https://downloads.dcos.io/dcos-launch/bin/mac/dcos-launch'
    else
        URL='https://downloads.dcos.io/dcos-launch/bin/linux/dcos-launch'
    fi

    wget "$URL" -P "$(pwd)/bin" && chmod +x "$(pwd)/bin/dcos-launch"
    export PATH
fi
