FROM debian:stretch-slim AS template
LABEL MAINTAINER="Mesosphere Package Builder <support@mesosphere.io>"
WORKDIR /marathon

ARG MESOS_PKG_VERSION

ENV JAVA_HOME /docker-java-home

RUN apt-get update && apt-get install -my wget gnupg && \
  apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv DF7D54CBE56151BF && \
  apt-get update -y && \
  apt-get upgrade -y && \
  echo "deb http://repos.mesosphere.com/debian stretch-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
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
  apt-get clean

RUN apt-get update
RUN apt-get install -y curl
RUN apt-get install -y apt-transport-https
RUN echo "deb https://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list && \
  curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
  apt-get update && \
  apt-get -y install sbt

USER root

FROM template as sbt
COPY target/marathon /marathon
RUN cd /marathon && sbt integration:test/update

FROM template
COPY --from=sbt /root /root

RUN curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/2.0.1/2.12-2.0.1 && chmod +x /usr/local/bin/amm
RUN ln -sf /usr/local/bin/amm /usr/local/bin/amm-2.12
RUN amm -c 'println("Pre-caching ammonite")'
