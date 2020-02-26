FROM amazoncorretto:8u242

RUN curl https://bintray.com/sbt/rpm/rpm | tee /etc/yum.repos.d/bintray-sbt-rpm.repo && \
  yum install -y sbt-1.2.8-0 git

RUN curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/2.0.1/2.12-2.0.1 && \
  chmod +x /usr/local/bin/amm && \
  ln -sf /usr/local/bin/amm /usr/local/bin/amm-2.12

# Warmup .ivy2 and .sbt cache. This requires a lot of memory.
RUN mkdir -p /var/tmp/.ivy2 && mkdir -p /var/tmp/.sbt \
  git clone https://github.com/mesosphere/marathon.git /tmp/marathon && cd /tmp/marathon && \
  sbt -Dsbt.global.base=/var/tmp/.sbt -Dsbt.boot.directory=/var/tmp/.sbt -Dsbt.ivy.home=/var/tmp/.ivy2 update && \
  rm -r /tmp/marathon
