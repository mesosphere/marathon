#!/bin/bash
set +x
sudo apt-get -y clean
sudo apt-get -y update

# Add sbt repo.
echo "deb https://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list
apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823

# Add Mesos repo.
sudo apt-get install -y lsb-release
sudo apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF
echo "deb http://repos.mesosphere.com/$(lsb_release -is | tr '[:upper:]' '[:lower:]') $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/mesosphere.list
sudo apt-get -y update

# Install dependencies.
sudo apt-get install -y --force-yes --no-install-recommends curl sbt

if grep -q MesosDebian project/Dependencies.scala; then
    MESOS_VERSION=$(sed -n 's/^.*MesosDebian = "\\(.*\\)"/\\1/p' < "$WORKSPACE/project/Dependencies.scala")
else
    MESOS_VERSION=$(sed -n 's/^.*mesos=\\(.*\\)&&.*/\\1/p' < "$WORKSPACE/Dockerfile")
fi
sudo apt-get install -y --force-yes --no-install-recommends mesos="$MESOS_VERSION"
