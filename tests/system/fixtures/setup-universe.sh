#!/bin/bash

# script dir
FIXTURE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

dcos marathon app add $FIXTURE_DIR/universe.json
dcos package repo add --index=0 marathon-test http://universe.marathon.mesos:8082/repo

echo "Universe with mesosphere/marathon:latest-dev is set at index 0"
echo "To remove other universes run 'dcos package repo remove Universe' and 'dcos package repo remove Universe-1.7'"
