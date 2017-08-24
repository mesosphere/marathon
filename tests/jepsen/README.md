# Marathon Jepsen

Automating the testing of various Marathon operations in a cluster in the presence of controlled network splits.

## What is Jepsen?

[Jepsen](https://jepsen.io) is a clojure library designed by Kyle Kingsbury, designed to test the partition tolerance of distributed systems.

Jepsen source code can be found on [GitHub](https://github.com/jepsen-io/jepsen)

## Tests

The entry point for each test is in the `tests/jepsen` directory.

The tests currently available in the suite are as follows (in alphabetical order):

1. [App Instance test](test/jepsen/app_instance_test.clj)
1. [Basic app test](test/jepsen/basic_app_test.clj)
1. [Basic pod test](test/jepsen/basic_pod_test.clj)
1. [Destroy app test](test/jepsen/destroy_app_test.clj)
1. [Groups and dependencies test](test/jepsen/groups_dependencies_test.clj)
1. [Leader abdication test](test/jepsen/leader_abdication_test.clj)
1. [Scale test](test/jepsen/scale_test.clj)

## Install Prerequisites

Here are the following prerequisites which are required to run the tests:
1. Java 8
1. Vagrant
1. [Leiningen](https://leiningen.org/#install)

## Usage

To spin-up VMs using `vagrant`:

```
vagrant up
```

This will spin-up 4 VM cluster for now for the purpose of testing.
To spin-up a cluster with different number of VMs, set the environment variable `JEPSEN_CLUSTER_SIZE`:

```
export JEPSEN_CLUSTER_SIZE=<number-of-VMs>
```

To download the Jepsen and other dependencies:

```
make deps
```

Makes sure the ip address of the VMs spun-up by vagrant are present in a file called `nodes_list` which is picked up by the test with the default username and password as `ubuntu`.
To run a test,

```
make test=jepsen.<test-name>
```

Example:

```
make test=jepsen.app-instance-test
```
