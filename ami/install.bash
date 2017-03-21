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

# Install dependencies
apt-get install -y \
    git \
    php5-cli \
    php5-curl \
    sbt \
    docker-engine \
    curl \
    build-essential \
    rpm \
    ruby \
    ruby-dev

apt install -t jessie-backports -y openjdk-8-jdk

# Install fpm which is used for deb and rpm packaging.
gem install fpm

# Download (but don't install) Mesos and its dependencies.
# The CI task will install Mesos later.
apt-get install -y -d mesos

# Add arcanist
mkdir -p /opt/arcanist
git clone https://github.com/phacility/libphutil.git /opt/arcanist/libphutil
git clone https://github.com/phacility/arcanist.git /opt/arcanist/arcanist
ln -sf /opt/arcanist/arcanist/bin/arc /usr/local/bin/

# Add user to docker group
gpasswd -a admin docker

# Setup system
systemctl enable docker
update-ca-certificates -f
update-java-alternatives -s java-1.8.0-openjdk-amd64

echo "{\"hosts\":{\"https://phabricator.mesosphere.com/api/\":{\"token\":\"$CONDUIT_TOKEN\"}}}" > /home/admin/.arcrc
chown admin /home/admin/.arcrc
curl -o /usr/bin/jq https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64

# Warmup ivy2 cache
git clone https://github.com/mesosphere/marathon.git /home/admin/marathon
su - admin -c "cd /home/admin/marathon && sbt update"
rm -rf /home/admin/marathon
