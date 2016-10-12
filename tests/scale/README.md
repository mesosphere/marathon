# Marathon Scale Testing

These tests are part of the system integration tests and require a configured DCOS cluster
in order to run.

Historically scale testing is a measure of the number of concurrent requests a service can handle or how the performance characteristics change over that load.   In the case of Marathon and DCOS, scale specifically refers to the performance characteristics of Marathon and it's ability to handle X number of tasks.   The number of tasks are controlled by 1) Number of Apps, 2) Number of Pods, 3) Number of instances of apps or pods and 4) the number of containers in a pod.

## Requirements

* DCOS Cluster (Open or Enterprise)
* Shakedown
* Python

The scale tests are integration tests with [DCOS](http://dcos.io).  These tests do NOT provide assertions which would cause these tests to fail.  These test provide standard output which must be reviewed and can be used to create performance reports.   The tests are written in python using [shakedown](https://github.com/dcos/shakedown) as the testing tool.

## Fixture

It is possible to over-provision a DCOS agent.   An automated way to accomplish this has been provided with over-provision.py and over-provision.sh.   This will cause an agent to claim it has 100 cores regardless of the number of physical cores it has.    The over-provision.py is specifically named without a "test_" prefix, because it is not a test and additional so it doesn't get picked up by shakedown by default.  It will run with shakedown but you have to explicitly reference it.

To run this: `shakedown --dcos-url=$(dcos config show core.dcos_url) --ssh-key-file=~/.ssh/default.pem --stdout all --stdout-inline ./tests/scale/over-provision.py`

## Scale tests

There are current 2 scale tests.

* test_marathon_scale.py
* test_pod_scale.py


To run:  `shakedown --dcos-url=$(dcos config show core.dcos_url) --ssh-key-file=~/.ssh/default.pem --stdout all --stdout-inline ./tests/scale/test_marathon_scale.py` or `shakedown --dcos-url=$(dcos config show core.dcos_url) --ssh-key-file=~/.ssh/default.pem --stdout all --stdout-inline ./tests/scale/test_pod_scale.py`
