FROM debian:stretch-slim
LABEL MAINTAINER="Mesosphere Package Builder <support@mesosphere.io>"
WORKDIR /marathon

ARG MESOS_PKG_VERSION

ENV JAVA_HOME /docker-java-home
ADD --chown=nobody:nogroup share/marathon /marathon

RUN apt-get update && apt-get install -my wget gnupg && \
  apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv DF7D54CBE56151BF && \
  apt-get update -y && \
  apt-get upgrade -y && \
  echo "deb http://repos.mesosphere.com/debian stretch-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
  echo "deb http://repos.mesosphere.com/debian stretch-unstable main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
  echo "deb http://repos.mesosphere.com/debian stretch main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
  apt-get update && \
  # jdk setup
  mkdir -p /usr/share/man/man1 && \
  apt-get install -y openjdk-8-jdk-headless openjdk-8-jre-headless ca-certificates-java && \
  /var/lib/dpkg/info/ca-certificates-java.postinst configure && \
  ln -svT "/usr/lib/jvm/java-8-openjdk-$(dpkg --print-architecture)" /docker-java-home && \
  # mesos setup
  echo exit 0 > /usr/bin/systemctl && chmod +x /usr/bin/systemctl && \
  # Workaround required due to https://github.com/mesosphere/mesos-deb-packaging/issues/102
  # Remove after upgrading to Mesos 1.7.0
  apt-get install -y libcurl3-nss && \
  apt-get install --no-install-recommends -y mesos=${MESOS_PKG_VERSION}.debian9 && \
  rm /usr/bin/systemctl && \
  apt-get clean && \
  chown nobody:nogroup /marathon && \
  ln -sf /marathon/bin/marathon /marathon/bin/start && \
  chmod a+x /marathon/bin/marathon

USER nobody
ENTRYPOINT ["/marathon/bin/marathon"]
CMD []
