FROM ubuntu:14.04

COPY ./mesos-version /mesos-version

RUN apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF && \
  DISTRO=$(lsb_release -is | tr '[:upper:]' '[:lower:]') && \
  CODENAME=$(lsb_release -cs) && \
  echo "deb http://repos.mesosphere.com/${DISTRO} ${CODENAME} main" | tee /etc/apt/sources.list.d/mesosphere.list && \

  # openjdk repo
  apt-key adv --keyserver keyserver.ubuntu.com --recv 86F44E2A && \
  echo "deb http://ppa.launchpad.net/openjdk-r/ppa/ubuntu trusty main"  | tee /etc/apt/sources.list.d/openjdk-r-ppa-trusty.list && \

  sudo apt-get update && \

  # install mesos
  # this MUST be done first, unfortunately, because Mesos packages will create folders that should be symlinks and break the python install process
  apt-get install python2.7-minimal -y && \
  apt-get install --no-install-recommends -y --force-yes mesos=$(cat /mesos-version) && \

  # disable mesos-master; we don't want to start in this image
  echo manual | tee /etc/init/mesos-master.override && \
  echo manual | tee /etc/init/mesos-slave.override && \

  # install openjdk8
  apt-get install -y openjdk-8-jdk-headless && \
  ln -svT "/usr/lib/jvm/java-8-openjdk-$(dpkg --print-architecture)" /docker-java-home && \

  # jq / curl
  apt-get install -y curl && \
  curl -L -o /usr/bin/jq https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 && \
  [ $(sha256sum /usr/bin/jq | cut -f 1 -d ' ') = "c6b3a7d7d3e7b70c6f51b706a3b90bd01833846c54d32ca32f0027f00226ff6d" ] && \
  chmod +x /usr/bin/jq && \

  # The Ubuntu image gives us an implementation of "true" for /sbin/initctl; switch it back. We actually want to use upstart.
  mv /sbin/initctl.distrib /sbin/initctl && \

  # clean up
  apt-get clean && \
  rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

ENV JAVA_HOME /docker-java-home

ENTRYPOINT ["/sbin/init"]
