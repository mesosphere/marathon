# Marathon Dockerfile
FROM ubuntu:precise
MAINTAINER Mesosphere <support@mesosphere.io>

## DEPENDENCIES ##
RUN apt-get update && apt-get install --assume-yes python-software-properties curl default-jdk

# install mesos (for libs) from mesosphere downloads
ADD http://downloads.mesosphere.io/master/ubuntu/12.04/mesos_0.18.2_amd64.deb /tmp/mesos.deb
RUN dpkg --install /tmp/mesos.deb && rm /tmp/mesos.deb

## MARATHON ##
ADD http://downloads.mesosphere.io/marathon/marathon-0.5.1/marathon-0.5.1.tgz /tmp/marathon.tgz
RUN mkdir -p /opt/marathon && tar xzf /tmp/marathon.tgz -C /opt/marathon --strip=1 && rm -f /tmp/marathon.tgz

EXPOSE 8080
WORKDIR /opt/marathon
CMD ["--help"]
ENTRYPOINT ["/opt/marathon/bin/start"]
