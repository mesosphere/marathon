# Marathon Testing

This directory contains system integration tests of marathon in a DCOS environment.

To run the test you need a DC/OS cluster, Ptyhon 3.5+, dcos-cli 0.5.5 and shakedown 1.4.8 installed.

First authenticate with your cluster with

```
dcos cluster setup --no-check --username=$DCOS_USER --password=$DCOS_PWD https://...
```

Then run a specific test with

```
shakedown test_marathon_root.py::test_private_repository_mesos_app
```
