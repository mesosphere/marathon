# Shakedown [![Build Status](http://jenkins.mesosphere.com/service/jenkins/buildStatus/icon?job=public-shakedown-master)](http://jenkins.mesosphere.com/service/jenkins/job/public-shakedown-master/)

DC/OS test harness.


## Overview

*A shakedown is a period of testing or a trial journey undergone by a ship, aircraft or other craft and its crew before being declared operational.
    — https://en.wikipedia.org/wiki/Shakedown_(testing)*


## Installation

Shakedown requires Python 3.6+.

### Installing from PyPI

The recommended Shakedown installation method is via the PyPI Python Package Index repository at [https://pypi.python.org/pypi/dcos-shakedown](https://pypi.python.org/pypi/dcos-shakedown).  To install the latest version and all required modules:

`pip3 install dcos-shakedown`

dcos-shakedown has a number of dependencies which need to be available.  One of those dependencies, the cryptography module requires a number of OS level libraries in order to install correctly which include: `build-essential libssl-dev libffi-dev python-dev`.  For environments other than linux please read [Stackoverflow](http://stackoverflow.com/questions/22073516/failed-to-install-python-cryptography-package-with-pip-and-setup-py). On a new ubuntu environment the following should install dcos-shakedown.

* `apt-get update`
* `apt-get install python3 python3-pip build-essential libssl-dev libffi-dev python-dev`
* `pip3 install dcos-shakedown`

### Development and bleeding edge

To pull and install from our `master` branch on GitHub:

```
git clone https://github.com/dcos/shakedown.git
cd shakedown
make init
```

## Usage and Configuration

Shakedown is a Python library that can be used with Pytest. It assumes the following environment variables

* `DCOS_URL`
* `DCOS_USERNAME` and `DCOS_PASSWORD` or `SHAKEDOWN_OAUTH_TOKEN`
* `SHAKEDOWN_SSH_KEY_FILE'` and `SHAKEDOWN_SSH_USER`.

These can also be stored in `~/.shakedown` as [TOML](https://github.com/toml-lang/toml)

* `oauth_token`
* `username` and `password`


## License

Shakedown is licensed under the Apache License, Version 2.0.  For additional information, see the [LICENSE](LICENSE) file included at the root of this repository.


## Reporting issues

Please report issues and submit feature requests for Shakedown by [creating an issue in the DC/OS JIRA with the "Shakedown" component](https://jira.mesosphere.com/secure/CreateIssueDetails!init.jspa?pid=14105&components=19807&issuetype=3) (JIRA account required).


## Contributing

See the [CONTRIBUTING](CONTRIBUTING.md) file in the root of this repository.
