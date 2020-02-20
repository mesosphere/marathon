FROM amazoncorretto:8u242

RUN curl https://bintray.com/sbt/rpm/rpm | tee /etc/yum.repos.d/bintray-sbt-rpm.repo && \
  yum install -y sbt

RUN curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/2.0.1/2.12-2.0.1 && \
  chmod +x /usr/local/bin/amm && \
  ln -sf /usr/local/bin/amm /usr/local/bin/amm-2.12
