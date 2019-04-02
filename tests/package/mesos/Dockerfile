FROM marathon-package-test:debian-systemd

COPY zookeeper.service /lib/systemd/system

RUN apt-get update -o Acquire::Check-Valid-Until=false && \
  apt-get install -y curl zookeeper && \
  systemctl enable zookeeper && \
  systemctl enable mesos-master

ENTRYPOINT ["/sbin/init"]
