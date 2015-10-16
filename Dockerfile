FROM java:8-jdk

RUN apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF && \
    echo "deb http://repos.mesosphere.io/debian jessie main" | tee /etc/apt/sources.list.d/mesosphere.list && \
    echo "deb http://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list && \
    apt-get update && \
    apt-get install --no-install-recommends -y --force-yes mesos=0.25.0-0.2.70.debian81 sbt

WORKDIR /marathon

# The build configuration including dependencies changes
# less frequently than the source code. By separating
# these steps we can greatly speed up cached local docker builds
COPY project /marathon/project
RUN sbt update test:update

COPY . /marathon

RUN sbt -Dsbt.log.format=false assembly && \
    mv $(find target -name 'marathon-assembly-*.jar' | sort | tail -1) ./ && \
    rm -rf target/* ~/.sbt ~/.ivy2 && \
    mv marathon-assembly-*.jar target

ENTRYPOINT ["./bin/start"]
