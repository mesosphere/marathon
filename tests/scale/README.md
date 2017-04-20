# Marathon Scale Testing

These tests are part of the system integration tests and require a configured DCOS cluster
in order to run.

Historically scale testing is a measure of the number of concurrent requests a service can handle or how the performance characteristics change over that load.   In the case of Marathon and DCOS, scale specifically refers to the performance characteristics of Marathon and it's ability to handle X number of tasks.   The number of tasks are controlled by 1) Number of Apps, 2) Number of Pods, 3) Number of instances of apps or pods and 4) the number of containers in a pod.

## Requirements

* DCOS Cluster (Open or Enterprise)
* Shakedown
* Python
  * numpy
  * matplotlib
  * click

The scale tests are system integration tests with [DCOS](http://dcos.io).  These tests do NOT provide assertions which would cause these tests to fail.  These test provide standard output which must be reviewed and can be used to create performance reports.   The tests are written in python using [shakedown](https://github.com/dcos/shakedown) as the testing tool.

## Fixture

It is possible to over-provision a DCOS agent.   An automated way to accomplish this has been provided with over-provision.py and over-provision.sh.   This will cause an agent to claim it has 100 cores regardless of the number of physical cores it has.    The over-provision.py is specifically named without a "test_" prefix, because it is not a test and additional so it doesn't get picked up by shakedown by default.  It will run with shakedown but you have to explicitly reference it.

To run this: `shakedown --dcos-url=$(dcos config show core.dcos_url) --ssh-key-file=~/.ssh/default.pem --stdout all --stdout-inline ./tests/scale/over-provision.py`

## Scale tests

There are current 2 scale tests.

* test_marathon_scale.py
* test_pod_scale.py


To run:  `shakedown --dcos-url=$(dcos config show core.dcos_url) --ssh-key-file=~/.ssh/default.pem --stdout all --stdout-inline ./tests/scale/test_marathon_scale.py` or `shakedown --dcos-url=$(dcos config show core.dcos_url) --ssh-key-file=~/.ssh/default.pem --stdout all --stdout-inline ./tests/scale/test_pod_scale.py`

## Scale Test Output

The scale tests provide as an output 3 files:

* [scale-test.csv](example/scale-test.csv) - a csv file of each of the scale tests
* [meta-data.json](example/meta-data.json) - the cluster under test information
* [scale.png](example/scale.png)  - a graph representation of the scale test data


## Graphing Scale Data

Graphing using [matplotlib](http://matplotlib.org/index.html) has been added to visualize the scale test data.   The output of running a scale test produces a scale.png visualization of the graph data.  The [graph.py](graph.py) is an executable which can produce an image when provided a csv file and meta-data file for a scale test.   Examples are in the [example](example) folder.   To execute the graph from this directory try:  `./graph.py --help`
**Note:** It may be necessary to `chmod +x graph.py`

```
./graph.py --help
Usage: graph.py [OPTIONS]

  CLI entry point for graphing scale data. Typically, scale tests create a
  scale-test.csv file which contains the graph points. It also produces a
  meta-data.json which is necessary for the graphing process.

Options:
  --csvfile TEXT       Name of csv file to graph
  --metadatafile TEXT  Name of meta-data file to use for graphing
  --graphfile TEXT     Name of graph to create
  --help               Show this message and exit.
```

Creating a graph with the same data:  `./graph.py --csvfile example/scale-test.csv --metadatafile example/meta-data.json`  
