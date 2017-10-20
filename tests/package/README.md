# Package tests

## Prerequisites

- Ammonite (v1.0.1 Scala 2.12 version tested; later should work too)
- Docker
- `rpmbuild`. Available via:
  - The `alien` package in Debian/Ubuntu distros
  - The `rpm-build` package in Redhat based distros
  - This [docker-based hack](https://gist.github.com/timcharper/f1f821fad32fac6751ddc7ce7bceb189) for OS X.

## Running

### 1) Build docker and all linux packages (deb/rpm)

Packages will land in `{marathon_project_dir}/target/packages`.

**Only one** version of the package should exist for each package format and service loader. You should clean the folder out prior to running.

Build with:

```
cd {marathon_project_dir}
rm -rf target/packages
sbt docker:publishLocal packageLinux
```

### 2) Build the test bed docker images

There are docker images created for:

  * centos systemd
  * centos systemv
  * debian systemd
  * debian systemv
  * ubuntu upstart

The Dockerfiles for these images in subfolders of this directory.  
These test bed docker images must be built prior to running the tests. If you don't build them, the tests will fail.

To build the prerequisite test bed docker images.

```
cd {marathon_project_dir}/tests/package
make all
```

### 3) Run the tests

**Check that again** - Exactly one version of each package should be in `{marathon_project_dir}/target/packages`.

```
# from {marathon_project_dir}/tests/package
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
