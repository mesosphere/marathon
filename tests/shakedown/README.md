# Shakedown [![Build Status](http://jenkins.mesosphere.com/service/jenkins/buildStatus/icon?job=public-shakedown-master)](http://jenkins.mesosphere.com/service/jenkins/job/public-shakedown-master/)

DC/OS test harness.


## Overview

*A shakedown is a period of testing or a trial journey undergone by a ship, aircraft or other craft and its crew before being declared operational.
    — https://en.wikipedia.org/wiki/Shakedown_(testing)*


## Installation

Shakedown requires Python 3.4+.

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
pip3 install -r requirements.txt && pip3 install -e .
```

Or if you do not wish to pin to a version of `dcos-cli`:

```
pip3 install -r requirements-edge.txt && pip3 install -e .
```

### Setting up a new Shakedown virtual environment

If you'd like to isolate your Shakedown Python environment, you can do so using the [virtualenv](https://pypi.python.org/pypi/virtualenv) tool.  To create a new virtual environment in `$HOME/shakedown`:

```
pip3 install virtualenv
virtualenv $HOME/shakedown
source $HOME/shakedown/bin/activate
pip3 install dcos-shakedown
```

This virtual environment can then be activated in new terminal sessions with:

`source $HOME/shakedown/bin/activate`


## Usage

`shakedown --dcos-url=http://dcos.example.com [options] [path_to_tests]`

- `--dcos-url` is required.
- tests within the current working directory will be auto-discovered unless specified.
- arguments can be stored in a `~/.shakedown` [TOML](https://github.com/toml-lang/toml) file (command-line takes precedence)
- `shakedown --help` is your friend.


### Running in parallel

Shakedown can be run against multiple DC/OS clusters in parallel by setting the `DCOS_CONFIG_ENV` environmental variable to a unique file, eg:

`DCOS_CONFIG_ENV='shakedown-custom-01.toml' shakedown --dcos-url=http://dcos.example.com [options] [path_to_tests]`


## Helper methods

Shakedown is a testing tool as well as a library.  Many helper functions are available via `from shakedown import *` in your tests.  See the [API documentation](API.md) for more information.


## License

Shakedown is licensed under the Apache License, Version 2.0.  For additional information, see the [LICENSE](LICENSE) file included at the root of this repository.


## Reporting issues

Please report issues and submit feature requests for Shakedown by [creating an issue in the DC/OS JIRA with the "Shakedown" component](https://jira.mesosphere.com/secure/CreateIssueDetails!init.jspa?pid=14105&components=19807&issuetype=3) (JIRA account required).


## Contributing

See the [CONTRIBUTING](CONTRIBUTING.md) file in the root of this repository.
