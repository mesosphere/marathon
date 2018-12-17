#!/usr/bin/env bash
set -euo pipefail

apt-get install -y dirmngr

# Add sbt repo.
echo "deb https://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list
apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823

# Add Docker repo.
echo "deb https://apt.dockerproject.org/repo debian-stretch main" | tee -a /etc/apt/sources.list.d/docker.list
apt-key adv --keyserver hkp://p80.pool.sks-keyservers.net:80 --recv-keys 58118E89F3A912897C070ADBF76221572C52609D

# Add Mesos repo.
apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv DF7D54CBE56151BF && \
  echo "deb http://repos.mesosphere.com/debian stretch-unstable main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
  echo "deb http://repos.mesosphere.com/debian stretch-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
  echo "deb http://repos.mesosphere.com/debian stretch main" | tee -a /etc/apt/sources.list.d/mesosphere.list
apt-get -y update

# Add github.com to known hosts
ssh-keyscan github.com >> /home/admin/.ssh/known_hosts
ssh-keyscan github.com >> /root/.ssh/known_hosts

# Install dependencies
apt-get install -y \
        build-essential \
        curl \
        docker-engine \
        git \
        openjdk-8-jdk \
        libssl-dev \
        rpm \
        sbt \
        zlib1g-dev

# Download, compile and install Python 3.6.2
wget https://www.python.org/ftp/python/3.6.2/Python-3.6.2.tgz
tar xvf Python-3.6.2.tgz && cd Python-3.6.2/
./configure --enable-optimizations
make -j
sudo make install
cd ../ && rm -r Python-3.6.2

# Install pip
wget https://bootstrap.pypa.io/get-pip.py
python3 get-pip.py

# Install falke8
pip3 install flake8

# Download (but don't install) Mesos and its dependencies.
# The CI task will install Mesos later.
apt-get install -y --force-yes --no-install-recommends mesos=$MESOS_VERSION
systemctl stop mesos-master.service mesos-slave.service mesos_executor.slice

# Add user to docker group
gpasswd -a admin docker

# Nodejs: add the NodeSource APT repository for Debian-based distributions repository AND the PGP key for verifying packages
curl -sL https://deb.nodesource.com/setup_6.x | bash -
apt-get install -y nodejs

# Setup system
systemctl enable docker
update-ca-certificates -f
systemctl stop apt-daily.timer
systemctl stop apt-daily-upgrade.timer

# Install jq
curl -L -o /usr/local/bin/jq https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 && sudo chmod +x /usr/local/bin/jq

# Install Ammonite
curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/1.5.0/2.12-1.5.0 && sudo chmod +x /usr/local/bin/amm

# Warmup ivy2 cache. Note: `sbt` is later executed with `sudo` and Debian `sudo` modifies $HOME
# so we need ivy2 cache in `/root`
git clone https://github.com/mesosphere/marathon.git /home/admin/marathon
cd /home/admin/marathon
sbt update
git checkout origin/releases/1.5
rm -rf $(find . -name target -type d)
sbt update
git checkout origin/releases/1.4
rm -rf $(find . -name target -type d)
sbt update
rm -rf /home/admin/marathon
