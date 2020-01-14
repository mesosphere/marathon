#!/usr/bin/env bash
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

# dirmngr is needed by multiple subsequent steps
apt-get -y update
apt-get install -y dirmngr

# Install sbt
echo -e "\n=== Install SBT ==="
echo "deb https://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list
apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823
apt-get -y update
apt-get install -y openjdk-8-jdk-headless sbt

# Install docker
echo -e "\n=== Install Docker ==="
apt-get install -y \
    apt-transport-https \
    ca-certificates \
    curl \
    gnupg2 \
    software-properties-common

curl -fsSL https://download.docker.com/linux/debian/gpg | sudo apt-key add -
add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/debian $(lsb_release -cs) stable"
apt-get -y update
apt-cache policy docker-ce
apt-get install -y docker-ce

# Add github.com to known hosts
ssh-keyscan github.com >> /home/admin/.ssh/known_hosts
ssh-keyscan github.com >> /root/.ssh/known_hosts

# Install Mesos
echo -e "\n=== Install Mesos ==="
apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv DF7D54CBE56151BF && \
  echo "deb http://repos.mesosphere.com/debian stretch-unstable main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
  echo "deb http://repos.mesosphere.com/debian stretch-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
  echo "deb http://repos.mesosphere.com/debian stretch main" | tee -a /etc/apt/sources.list.d/mesosphere.list
apt-get -y update

# Install but do not start Mesos master/slave processes
# The CI task will install Mesos later.
apt-get install -y --no-install-recommends mesos=$MESOS_VERSION
systemctl stop mesos-master.service mesos-slave.service
systemctl disable mesos-master.service mesos-slave.service

echo "=== Install Python 3.6.2, Pip and Flake 8==="
apt-get install -y \
        build-essential \
        git \
        openjdk-8-jdk \
        libssl-dev \
        rpm \
        zlib1g-dev

# Download, compile and install Python 3.6.2
wget https://www.python.org/ftp/python/3.6.2/Python-3.6.2.tgz
tar xvf Python-3.6.2.tgz && cd Python-3.6.2/
./configure
make -j
sudo make install
cd ../ && rm -r Python-3.6.2
# Use this instead of the manual python compile when we switch to buster base image. Make sure we get Python3.6
#echo -e "\n=== Install Python 3, Pip and Flake8 ==="
#apt-get install python3-distutils python3 -y

# Install Pip and Flake8
wget https://bootstrap.pypa.io/get-pip.py
python3 get-pip.py
pip3 install flake8


# Add user to docker group
gpasswd -a admin docker

# Install Nodejs: add the NodeSource APT repository for Debian-based distributions repository AND the PGP key for verifying packages
echo -e "\n=== Install Nodejs ==="
curl -sL https://deb.nodesource.com/setup_10.x | bash -
apt-get install -y nodejs

# Setup system
systemctl enable docker
update-ca-certificates -f
systemctl stop apt-daily.timer apt-daily-upgrade.timer
systemctl disable apt-daily.timer apt-daily-upgrade.timer

# Install jq
echo -e "\n=== Install Jq ==="
curl -L -o /usr/local/bin/jq https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 && sudo chmod +x /usr/local/bin/jq

# Install Ammonite
echo -e "\n=== Install Ammonite ==="
curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/2.0.1/2.12-2.0.1 && sudo chmod +x /usr/local/bin/amm
sudo cp /usr/local/bin/amm /usr/local/bin/amm-2.12

# Warmup ivy2 cache. Note: `sbt` is later executed with `sudo` and Debian `sudo` modifies $HOME
# so we need ivy2 cache in `/root`
echo -e "\n=== Warmup SBT and Ammonite for master ==="
git clone https://github.com/mesosphere/marathon.git /home/admin/marathon
cd /home/admin/marathon
sbt update

echo -e "\n=== Install Utils ==="
apt-get install strace

# This throws an error without a parameter, but still fetches all required dependencies
ci/pipeline || true
