# Marathon Performance Tests

These tests can either run against a provisioned DC/OS Cluster, or locally using mesos simulator.

Historically scale testing is a measure of the number of concurrent requests a service can handle or how the performance characteristics change over that load. In the case of Marathon and DCOS, scale specifically refers to the performance characteristics of Marathon and it's ability to handle X number of tasks.   The number of tasks are controlled by

1) Number of Apps or Pods
2) Number of Instances of Apps or Pods
3) The complexity of the definition

## Requirements

* [DC/OS Performance Test Driver](https://github.com/mesosphere/dcos-perf-test-driver)
* One of:
    * A provisioned DC/OS Cluster (Enterprise or Open)
    * A valid development environment that can run a development version of marathon

To run the tests you can simply use the `dcos-perf-test-driver` tool. Some configurations require additional variables to be defined, for example:

```
dcos-perf-test-driver \
    ./config/perf-driver/test-n-apps-n-instances-local.yml \
    ./config/perf-driver/environments/cluster-oss.yml \
    --define base_url="http://my-dcos-cluster.io"
```

If there are errors you would like to investigate you can run the driver in verbose mode:

```
dcos-perf-test-driver \
    ./config/perf-driver/test-n-apps-n-instances-local.yml \
    ./config/perf-driver/environments/cluster-oss.yml \
    --define base_url="http://my-dcos-cluster.io" \
    --verbose
```

## Scale Tests

The following scale tests are available:

* `config/perf-driver/test-n-apps-1-instances.yml` : Starts 50, 100, 150, ... 500 apps with 1 instance and measures:
    - _Per-App Deployment Time_ : How long a deployment takes from the time the request is completed up to the deployment success or failure event
    - _Overall Deployment Time_ : How long the bunch of deployments took to complete, from the completion of the first request til the last deployment success or failure event.
    - _Failed Deployments_ : How many deployments failed
    - _Groups Endpoint Response_ : How long does an HTTP request to `/v2/groups` endpoint take to complete.
    - _CPU Load_ : The CPU load marathon caused while running.
    - _Memory Usage_ : The amount of memory consumed by marathon while running.
    - _Thread Count_ : The number of threads in the JVM while marathon was running.

* `config/perf-driver/test-1-apps-n-instances.yml` : Starts an app with 50, 100, 150, ... 500  1 instances and measures:
    - _Per-App Deployment Time_ : How long a deployment takes from the time the request is completed up to the deployment success or failure event
    - _Overall Deployment Time_ : How long the bunch of deployments took to complete, from the completion of the first request til the last deployment success or failure event.
    - _Failed Deployments_ : How many deployments failed
    - _Groups Endpoint Response_ : How long does an HTTP request to `/v2/groups` endpoint take to complete.
    - _CPU Load_ : The CPU load marathon caused while running.
    - _Memory Usage_ : The amount of memory consumed by marathon while running.
    - _Thread Count_ : The number of threads in the JVM while marathon was running.

## Test Results

The test is producing a variety of results:

* _dump.json_ - Contains the raw dump of the metrics collected for various parameters during the scale test.
* _results.csv_ - Contains a CSV with the summarized results for every parameter configuration.
* _plot-*.png_ - Image plots with the scale test results. Every metric will have its own image plot generated.
* _S3 RAW Upload_ - In addition to the local raw dump, the results are uploaded to S3 for long-term archiving.
* _Postgres Database_ - When used in CI, the results are posted in a Postgres database for long-term archiving and plotting through a PostgREST endpoint.
* _Datadog_ - When used in CI, a single "indicator" result will be submitted to Datadog for long-term archiving and alerting.

## Running in CI

All of the CI automation is implemented as a set of `ci_*.sh` scripts. These scripts require some configuration environment variables and are appropriately configuring and launching `dcos-scale-test-driver`.

This section describes the usage and requirements of each script.

### `ci_run_dcluster.sh` - Run tests against a thin cluster

Installs missing dependencies, creates a local cluster  using the [marathon-dcluster](https://github.com/wavesoft/marathon-dcluster) script and then it runs the tests on that cluster.

The "thin cluster" deployed consists of 6 Mesos agents (with overcommitted resources), 1 Mesos master, 1 Zookeeper and 1 Mesos containers using docker-compose. 

#### Requirements

* python3
* python3-pip
* docker
* docker-compose

#### Environment variables

* `PARTIAL_TESTS` _[Optional]_ : A comma-separated list with the names of the tests to run. If missing the full set of tests will run.
