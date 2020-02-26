FROM amazoncorretto:8u242

USER root
ENV SBT_VERSION=1.2.8 \
    SBT_HOME=/usr/local/sbt
ENV PATH=${SBT_HOME}/bin:${PATH}

# Install sbt
RUN yum install -y curl ca-certificates bash git tar
RUN curl -sL /tmp/sbt-${SBT_VERSION}.tgz "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | \
    gunzip | tar -x -C /usr/local

# Warm up caches. This requires ~8gb of memroy.
RUN git clone https://github.com/mesosphere/marathon.git && cd marathon && \
    sbt -Dsbt.log.noformat=true +compile test:compile;
