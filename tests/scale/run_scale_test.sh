#!/usr/bin/env bash

DCOS_ENDPOINT=$1
TIMEOUT=0 = no timeout
shakedown -o all -s -u $DCOS_ENDPOINT \
    --timeout $TIMEOUT \
    --username bootstrapuser --password deleteme \
    --pytest-option "-s" \
    test_marathon_cap.py::test_incremental_apps_per_group_scale
