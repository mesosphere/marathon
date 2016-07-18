#
# This is the official docker image that is used for production deployments of docker.
#
# It has the marathon startup script as entrypoint.
#
# It will reresolve all dependencies on every change (as opposed to Dockerfile.development)
# but it ultimately results in a smaller docker image.
#
FROM java:8-jdk


COPY . /marathon
WORKDIR /marathon

RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF && \
    echo "deb http://repos.mesosphere.com/debian jessie-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    echo "deb http://repos.mesosphere.com/debian jessie main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    apt-get update && \
    apt-get install --no-install-recommends -y --force-yes mesos=1.0.0-1.0.73.rc2.debian81 && \
    apt-get clean && \
    eval $(sed s/sbt.version/SBT_VERSION/ </marathon/project/build.properties) && \
    mkdir -p /usr/local/bin && \
    wget -P /usr/local/bin/ http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/$SBT_VERSION/sbt-launch.jar && \
    cp /marathon/project/sbt /usr/local/bin && chmod +x /usr/local/bin/sbt && \
    sbt -Dsbt.log.format=false assembly && \
    mv $(find target -name 'marathon-assembly-*.jar' | sort | tail -1) ./ && \
    rm -rf target/* ~/.sbt ~/.ivy2 && \
    mv marathon-assembly-*.jar target && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

ENTRYPOINT ["./bin/start"]
