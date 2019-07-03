FROM jrei/systemd-debian:9

COPY ./mesos-version /mesos-version

RUN apt-get -y update && apt-get -y install gnupg2
RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv DF7D54CBE56151BF && \
    echo "deb http://repos.mesosphere.com/debian stretch-unstable main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    echo "deb http://repos.mesosphere.com/debian stretch-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    echo "deb http://repos.mesosphere.com/debian stretch main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    apt-get -o Acquire::Check-Valid-Until=false update && \
    # this MUST be done first, unfortunately, because Mesos packages will create folders that should be symlinks and break the python install process
    apt-get install python2.7-minimal -y && \
    apt-get install -y openjdk-8-jdk-headless openjdk-8-jre-headless ca-certificates && \
    # Workaround required due to https://github.com/mesosphere/mesos-deb-packaging/issues/102
    # Remove after upgrading to Mesos 1.7.0
    # apt-get install -y libcurl3-nss && \
    apt-get install --no-install-recommends -y --force-yes mesos="$(cat /mesos-version).debian9" && \

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
