FROM amazoncorretto:8u242 as downloader

USER root
ENV SBT_VERSION=1.2.8 \
    SBT_HOME=/usr/local/sbt
ENV PATH=${SBT_HOME}/bin:${PATH}

RUN yum install -y curl ca-certificates bash git tar
RUN curl -sL /tmp/sbt-${SBT_VERSION}.tgz "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | \
    gunzip | tar -x -C /usr/local

RUN git clone https://github.com/mesosphere/marathon.git && cd marathon && \
    sbt -Dsbt.log.noformat=true +compile test:compile;

FROM amazoncorretto:8u242
COPY --from=downloader /usr/local/sbt /usr/local/sbt
COPY --from=downloader /root/.cache/coursier /root/.cache/coursier
COPY --from=downloader /root/.ivy2 /root/.ivy2
COPY --from=downloader /root/.sbt /root/.sbt
ENV PATH=${SBT_HOME}/bin:${PATH}
