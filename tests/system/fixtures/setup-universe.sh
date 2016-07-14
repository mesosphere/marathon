#!/bin/bash

# script dir
FIXTURE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

dcos marathon app add $FIXTURE_DIR/universe.json
dcos package repo add --index=0 marathon-test http://universe.marathon.mesos:8085/repo-1.7
