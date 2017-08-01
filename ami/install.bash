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
        git \
        php5-cli \
        php5-curl \
        sbt \
        docker-engine \
        curl \
        build-essential \
        rpm \
        npm

# Download (but don't install) Mesos and its dependencies.
# The CI task will install Mesos later.
apt-get install -y --force-yes --no-install-recommends mesos=$MESOS_VERSION

# Add arcanist
mkdir -p /opt/arcanist
git clone https://github.com/phacility/libphutil.git /opt/arcanist/libphutil
git clone https://github.com/phacility/arcanist.git /opt/arcanist/arcanist
ln -sf /opt/arcanist/arcanist/bin/arc /usr/local/bin/

# Add user to docker group
gpasswd -a admin docker

# Nodejs: add the NodeSource APT repository for Debian-based distributions repository AND the PGP key for verifying packages
curl -sL https://deb.nodesource.com/setup_6.x | bash -
apt-get install -y nodejs

# Setup system
systemctl enable docker
update-ca-certificates -f

echo "{\"hosts\":{\"https://phabricator.mesosphere.com/api/\":{\"token\":\"$CONDUIT_TOKEN\"}}}" > /home/admin/.arcrc
chown admin /home/admin/.arcrc
chmod 0600 /home/admin/.arcrc

# Install jq
curl -L -o /usr/local/bin/jq https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 && sudo chmod +x /usr/local/bin/jq

# Install Ammonite
curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/0.8.2/2.12-0.8.2 && sudo chmod +x /usr/local/bin/amm

# Warmup ivy2 cache. Note: `sbt` is later executed with `sudo` and Debian `sudo` modifies $HOME
# so we need ivy2 cache in `/root`
git clone https://github.com/mesosphere/marathon.git /home/admin/marathon
cd /home/admin/marathon
sbt update
rm -rf /home/admin/marathon
