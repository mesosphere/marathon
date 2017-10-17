# Marathon Testing

This directory contains system integration tests of marathon in a DCOS environment.

To run the test you need a DC/OS cluster, Ptyhon 3.5+, dcos-cli and shakedown installed.

First authenticate with your cluster with

```
dcos cluster setup --no-check --username=$DCOS_USER --password=$DCOS_PWD https://...
```

Then run a test with

```
shakedown test_marathon...py
```


dcoscli.version=0.5.5
shakedown, version 1.4.8
    Python 3.5.2
