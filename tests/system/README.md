# Marathon Testing

This directory contains system integration tests of marathon in a DCOS environment.

To run the test you need a DC/OS cluster, Ptyhon 3.5+, dcos-cli 0.5.5 and shakedown 1.4.8 installed.

To run a specific test:

```
# Change to marathon system tests
$ cd ~/marathon/tests/system

# if you're running tests against the strict cluster download the certificate 
$ wget --no-check-certificate -O fixtures/dcos-ca.crt http://xxx.amazonaws.com/ca/dcos-ca.crt

# otherwise DCOS_SSL_VERIFY can be omited from the arguments. Don't forget to set all the env vars below:
$ DCOS_URL="http://xxx.amazonaws.com" \
DCOS_USERNAME= \
DCOS_PASSWORD= \
DCOS_SSL_VERIFY="$(pwd)/fixtures/dcos-ca.crt" \
SHAKEDOWN_SSH_KEY_FILE="" \ 
SHAKEDOWN_SSH_USER=core \
pipenv run pytest --junitxml="../../shakedown.xml" -v -x --capture=no --full-trace --log-level=DEBUG --nf test_marathon_root.py::test_foo
```
