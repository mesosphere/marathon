# Package tests

## Prerequisites

- Ammonite (v1.0.1 Scala 2.12 version tested; later should work too)
- Docker
- rpmbuild (discussed later)

## Running

### 1) Build all packages

This will require the `rpmbuild` executable, available via:

- The `alien` package in debian distros
- The `rpm-build` package in redhat-based distros
- This [docker-based back](https://gist.github.com/timcharper/f1f821fad32fac6751ddc7ce7bceb189) for OS X.

Packages should be in folder `../../target/packages`

**Only one** version of the package should exist for each package format and service loader. You should clean the folder out prior to running.

Build with:

```
cd ../../
rm -rf target/packages
sbt docker:publishLocal packageLinux
```

### 2) Build the docker images

The docker images must be built prior to running the tests. If you don't build them, the tests will fail.

Simply run `make all` to build the prerequisite docker images.

### 3) Run the tests

**Check that again** - Exactly one version of each package should be in `../target/packages`.

```
amm test.sc all
```

# Running tests individually

You can run a single test; run test.sc with no params to see the help

```
amm test.sc
```

If you wanted to run just the DebianSystemd test, you could run:

```
# case-insensitive substring filter
amm test.sc debiansystemd
```

# Debugging

If you wanted to the docker images to stay running for further debugging, then use the filter to run a single suite and set the environment variable to disable cleanup:

```
SKIP_CLEANUP=1 amm test.sc debiansystemd
```

The docker containers will be left running after the suite runs, so you can `docker exec` in to them and check things out.
