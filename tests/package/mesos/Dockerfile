FROM marathon-package-test:debian8

COPY zookeeper.service /lib/systemd/system

RUN apt-get update && \
  apt-get install -y curl zookeeper libcurl3-nss && \
  systemctl enable zookeeper && \
  systemctl enable mesos-master

ENTRYPOINT ["/sbin/init"]
