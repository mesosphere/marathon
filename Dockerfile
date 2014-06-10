# Marathon Dockerfile
FROM ubuntu:14.04
MAINTAINER Mesosphere <support@mesosphere.io>

## DEPENDENCIES ##
RUN apt-get update && apt-get install --assume-yes curl default-jdk
RUN curl -Lo sbt.deb 'http://dl.bintray.com/sbt/debian/sbt-0.13.2.deb' && dpkg --install sbt.deb && rm sbt.deb

# install Mesos (for libs) from Mesosphere downloads
RUN curl -Lo mesos.deb 'http://downloads.mesosphere.io/master/ubuntu/14.04/mesos_0.19.0~ubuntu14.04%2B1_amd64.deb' && dpkg --install mesos.deb && rm mesos.deb

## MARATHON ##
ADD . /opt/marathon
RUN cd /opt/marathon && sbt assembly

EXPOSE 8080
WORKDIR /opt/marathon
CMD ["--help"]
ENTRYPOINT ["/opt/marathon/bin/start"]
