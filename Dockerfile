FROM debian:stretch-slim
LABEL MAINTAINER="Mesosphere Package Builder <support@mesosphere.io>"

COPY . /marathon
WORKDIR /marathon

RUN cat /marathon/project/Dependencies.scala  | grep MesosDebian | cut -f 2 -d '"' > /marathon/MESOS_VERSION && \
  cat /marathon/version.sbt | cut -f 2 -d '"' > /marathon/MARATHON_VERSION

RUN apt-get update && apt-get install -my wget gnupg && \
    apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv DF7D54CBE56151BF && \
    echo "deb http://ftp.debian.org/debian stretch-backports main" | tee -a /etc/apt/sources.list && \
    echo "deb http://repos.mesosphere.com/debian stretch-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    echo "deb http://repos.mesosphere.com/debian stretch main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    apt-get update && \
    apt-get upgrade -y && \
    # jdk setup
    mkdir -p /usr/share/man/man1 && \
    apt-get install -y openjdk-8-jdk-headless openjdk-8-jre-headless ca-certificates-java=20170531+nmu1 && \
    /var/lib/dpkg/info/ca-certificates-java.postinst configure && \
    ln -svT "/usr/lib/jvm/java-8-openjdk-$(dpkg --print-architecture)" /docker-java-home && \
    # mesos setup
    echo exit 0 > /usr/bin/systemctl && chmod +x /usr/bin/systemctl && \
    apt-get install --no-install-recommends -y mesos=$(cat /marathon/MESOS_VERSION) && \
    rm /usr/bin/systemctl && \
    # marathon setup
    apt-get install -y curl && \
    mkdir -p /tmp/marathon && curl https://downloads.mesosphere.com/marathon/v$(cat MARATHON_VERSION)/marathon-$(cat MARATHON_VERSION).tgz | tar zx -C /tmp/marathon && \
    mv /tmp/marathon/*/target /marathon/target && \

    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

ENV JAVA_HOME /docker-java-home
ENTRYPOINT ["./bin/start"]
