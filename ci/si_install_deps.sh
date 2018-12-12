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
    curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/1.5.0/2.12-1.5.0 && \
    chmod +x /usr/local/bin/amm && \
    echo "Ammonite successfully installed"
fi

# Ensure timeout is available.
if ! command -v timeout >/dev/null 2>&1; then
    if [ "$PLATFORM" == 'Darwin' ]; then
        brew install coreutils
        PATH="/usr/local/opt/coreutils/libexec/gnubin:$PATH"
        export PATH
    fi
fi

# Ensure the latest DC/OS CLI is available
if ! command -v dcos >/dev/null 2>&1; then
    if [ "$PLATFORM" == 'Darwin' ]; then
	curl -o /usr/local/bin/dcos https://downloads.dcos.io/binaries/cli/darwin/x86-64/latest/dcos
    else
	curl -o /usr/local/bin/dcos https://downloads.dcos.io/binaries/cli/linux/x86-64/latest/dcos
    fi
    chmod +x /usr/local/bin/dcos
fi

# Install dcos-launch and test dependencies.
make init
