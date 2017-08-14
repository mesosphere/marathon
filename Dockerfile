#
# This is the official docker image that is used for production deployments of docker.
#
# It has the marathon startup script as entrypoint.
#
# It will reresolve all dependencies on every change (as opposed to Dockerfile.development)
# but it ultimately results in a smaller docker image.
#
FROM buildpack-deps:jessie-curl

COPY . /marathon
WORKDIR /marathon

RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF && \
    echo "deb http://ftp.debian.org/debian jessie-backports main" >> /etc/apt/sources.list && \
    echo "deb http://repos.mesosphere.com/debian jessie-unstable main" | tee /etc/apt/sources.list.d/mesosphere.list && \
    echo "deb http://repos.mesosphere.com/debian jessie-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    echo "deb http://repos.mesosphere.com/debian jessie main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \
    MESOS_VERSION=$(sed -n 's/^.*MesosDebian = "\(.*\)"/\1/p' </marathon/project/Dependencies.scala) && \
    apt-get update && \
    apt-get install -y openjdk-8-jdk-headless openjdk-8-jre-headless ca-certificates-java=20161107~bpo8+1 && \
    apt-get install --no-install-recommends -y --force-yes mesos=$MESOS_VERSION && \
    apt-get clean && \
    eval $(sed s/sbt.version/SBT_VERSION/ </marathon/project/build.properties) && \
    mkdir -p /usr/local/bin && \
    wget -P /usr/local/bin/ http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/$SBT_VERSION/sbt-launch.jar && \
    cp /marathon/project/sbt /usr/local/bin && chmod +x /usr/local/bin/sbt && \
    sbt -Dsbt.log.format=false assembly && \
    mv $(find target -name 'marathon-assembly-*.jar' | sort | tail -1) ./ && \
    rm -rf project/target project/project/target plugin-interface/target target/* ~/.sbt ~/.ivy2 && \
    mv marathon-assembly-*.jar target && \
    /var/lib/dpkg/info/ca-certificates-java.postinst configure && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

ENTRYPOINT ["./bin/start"]
