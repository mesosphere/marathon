#!/usr/bin/env bash
set -euo pipefail

# Enable Jessie backports to install the latest Java JRE.
cat <<EOF >>/etc/apt/sources.list

# Debian backports
deb http://httpredir.debian.org/debian jessie-backports main
EOF

# Add sbt repo.
echo "deb https://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list
apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823

# Add Docker repo.
echo "deb https://apt.dockerproject.org/repo debian-jessie main" | tee -a /etc/apt/sources.list.d/docker.list
apt-key adv --keyserver hkp://p80.pool.sks-keyservers.net:80 --recv-keys 58118E89F3A912897C070ADBF76221572C52609D

# Add Mesos repo.
apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF && \
  echo "deb http://repos.mesosphere.com/debian jessie-unstable main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
  echo "deb http://repos.mesosphere.com/debian jessie-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
  echo "deb http://repos.mesosphere.com/debian jessie main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \

  apt-get -y update

# Add github.com to known hosts
ssh-keyscan github.com >> /home/admin/.ssh/known_hosts
ssh-keyscan github.com >> /root/.ssh/known_hosts

# Install dependencies
apt install -t jessie-backports -y openjdk-8-jdk
update-java-alternatives -s java-1.8.0-openjdk-amd64

apt-get install -y \
        build-essential \
        curl \
        docker-engine \
        git \
        npm \
        python3-pip \
        rpm \
        sbt

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

# Install jq
curl -L -o /usr/local/bin/jq https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 && sudo chmod +x /usr/local/bin/jq

# Install Ammonite
curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/0.8.2/2.12-0.8.2 && sudo chmod +x /usr/local/bin/amm

# Install falke8
pip3 install flake8

# Warmup ivy2 cache. Note: `sbt` is later executed with `sudo` and Debian `sudo` modifies $HOME
# so we need ivy2 cache in `/root`
git clone https://github.com/mesosphere/marathon.git /home/admin/marathon
cd /home/admin/marathon
sbt update
rm -rf /home/admin/marathon
