FROM debian:jessie

COPY ./mesos-version /mesos-version

RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF && \
    echo "deb http://ftp.debian.org/debian jessie-backports main" >> /etc/apt/sources.list && \
    echo "deb http://repos.mesosphere.com/debian jessie-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    echo "deb http://repos.mesosphere.com/debian jessie main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    apt-get update && \
    # this MUST be done first, unfortunately, because Mesos packages will create folders that should be symlinks and break the python install process
    apt-get install python2.7-minimal -y && \
    apt-get install -y openjdk-8-jdk-headless openjdk-8-jre-headless ca-certificates-java=20161107~bpo8+1 && \
    apt-get install --no-install-recommends -y --force-yes mesos=$(cat /mesos-version) && \

    # disable mesos-master; we don't want to start in this image
    systemctl disable mesos-master && \
    systemctl disable mesos-slave && \

    # jdk setup
    /var/lib/dpkg/info/ca-certificates-java.postinst configure && \
    ln -svT "/usr/lib/jvm/java-8-openjdk-$(dpkg --print-architecture)" /docker-java-home && \

    # jq / curl
    apt-get install -y procps curl jq=1.5* && \

    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

ENV JAVA_HOME /docker-java-home

ENTRYPOINT ["/sbin/init"]
