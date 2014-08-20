# Marathon Dockerfile
FROM ubuntu:14.04
MAINTAINER Mesosphere <support@mesosphere.io>

## DEPENDENCIES ##
RUN echo "deb http://repos.mesosphere.io/ubuntu/ trusty main" > /etc/apt/sources.list.d/mesosphere.list
RUN apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF
RUN apt-get update
RUN apt-get install --assume-yes mesos python-software-properties curl default-jdk

## MARATHON ##
ADD http://downloads.mesosphere.io/marathon/marathon-0.6.1/marathon-0.6.1.tgz /tmp/marathon.tgz
RUN mkdir -p /opt/marathon && tar xzf /tmp/marathon.tgz -C /opt/marathon --strip=1 && rm -f /tmp/marathon.tgz

EXPOSE 8080
WORKDIR /opt/marathon
CMD ["--help"]
ENTRYPOINT ["/opt/marathon/bin/start"]
