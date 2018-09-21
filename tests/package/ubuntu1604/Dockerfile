FROM ubuntu:16.04

COPY ./mesos-version /mesos-version

RUN apt-get update && apt-get install -my wget gnupg lsb-release && \
    apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv DF7D54CBE56151BF && \

    DISTRO=$(lsb_release -is | tr '[:upper:]' '[:lower:]') && \
    CODENAME=$(lsb_release -cs) && \
    echo "deb http://repos.mesosphere.com/${DISTRO} ${CODENAME} main" | tee /etc/apt/sources.list.d/mesosphere.list && \
    apt-get update && \

    # this MUST be done first, unfortunately, because Mesos packages will create folders that should be symlinks and break the python install process
    apt-get install python2.7-minimal -y && \
    apt-get install -y openjdk-8-jdk-headless openjdk-8-jre-headless && \
    apt-get install --no-install-recommends -y --force-yes mesos=$(cat /mesos-version) && \

    # disable mesos-master; we don't want to start in this image
    systemctl disable mesos-master && \
    systemctl disable mesos-slave && \

    # jq / curl
    apt-get install -y procps curl jq=1.5* && \

    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

ENV JAVA_HOME /docker-java-home

ENTRYPOINT ["/sbin/init"]
