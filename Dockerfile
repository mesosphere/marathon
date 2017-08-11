#
# This is the official docker image that is used for production deployments of docker.
#
# It has the marathon startup script as entrypoint.
#
# It will reresolve all dependencies on every change (as opposed to Dockerfile.development)
# but it ultimately results in a smaller docker image.
#
FROM openjdk:8-jdk

COPY . /marathon
WORKDIR /marathon

# TODO: line below starting touch /usr/local/bin/systemctl is a necessary hack for the installation
# of mesos.  We need to find a better solution. https://jira.mesosphere.com/browse/MARATHON-7694

RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF && \
    touch /usr/local/bin/systemctl && chmod +x /usr/local/bin/systemctl && \
    echo "deb http://repos.mesosphere.com/debian jessie-unstable main" | tee /etc/apt/sources.list.d/mesosphere.list && \
    echo "deb http://repos.mesosphere.com/debian jessie-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    echo "deb http://repos.mesosphere.com/debian jessie main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    MESOS_VERSION=$(sed -n 's/^.*MesosDebian = "\(.*\)"/\1/p' </marathon/project/Dependencies.scala) && \
    apt-get update && \
    apt-get install --no-install-recommends -y --force-yes mesos=$MESOS_VERSION && \
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
