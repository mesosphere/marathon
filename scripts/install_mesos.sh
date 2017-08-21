#!/usr/bin/env bash

# Setup
sudo apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF
DISTRO=$(lsb_release -is | tr '[:upper:]' '[:lower:]')
CODENAME=$(lsb_release -cs)

# Add the repository
echo "deb http://repos.mesosphere.com/${DISTRO} ${CODENAME} main" | 
  sudo tee /etc/apt/sources.list.d/mesosphere.list
  sudo apt-get -y update

sudo apt-get install -y --force-yes --no-install-recommends mesos=1.2.0-2.0.6
sudo systemctl stop mesos-master.service mesos-slave.service mesos_executor.slice
