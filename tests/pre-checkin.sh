#!/usr/bin/env bash
#
# Run Marathon tests with support of the Universal Container Runtime.
# This isn't possible in a Docker container.
# The script expects a Debian 8 environment where Java, Docker and sbt
# have been installed. It will then install Mesos and run all tests.
# The WORKSPACE environment variable has to be set to point to the
# Marathon directory. This variable is also set by Jenkins.
# The Mesosphere repository needs to be added prior to running this script.
# This can be done by running
# > apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF
# > echo "deb http://repos.mesosphere.com/debian jessie-unstable main" | tee -a /etc/apt/sources.list.d/mesosphere.list
# > echo "deb http://repos.mesosphere.com/debian jessie-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list
# > echo "deb http://repos.mesosphere.com/debian jessie main" | tee -a /etc/apt/sources.list.d/mesosphere.list
set +x -e -o pipefail

sudo apt-get -y update

# Runtime dependencies of Mesos
sudo apt-get install -y --force-yes --no-install-recommends curl

MESOS_VERSION=$(sed -n 's/^.*MesosDebian = "\(.*\)"/\1/p' <$WORKSPACE/project/Dependencies.scala)
sudo apt-get install -y --force-yes --no-install-recommends mesos=$MESOS_VERSION

# Cleanup
sudo rm -rf $WORKSPACE/target/*
sudo rm -rf $WORKSPACE/project/target/*
sudo rm -rf $WORKSPACE/mesos-simulation/target/*

export RUN_DOCKER_INTEGRATION_TESTS=true
export RUN_MESOS_INTEGRATION_TESTS=true

# Integration tests using the Mesos containerizer have to be run as superuser.
sudo -E sbt -Dsbt.log.format=false clean coverage test coverageReport compile scapegoat doc integration:test mesos-simulation/integration:test
sudo mv target/scala-2.11/scoverage-report target/scala-2.11/scoverage-report-stable
sudo mv target/scala-2.11/coverage-report/cobertura.xml target/scala-2.11/cobertura-stable.xml
sudo -E sbt -Dsbt.log.format=false clean coverage unstable:test unstable-integration:test coverageReport || true
