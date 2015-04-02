FROM mesosphere/mesos:0.22.0-1.0.ubuntu1404

RUN apt-get update && \
    apt-get upgrade -y && \
    apt-get install --no-install-recommends -y \
    default-jdk \
    scala \
    curl

RUN curl -SsL -O http://dl.bintray.com/sbt/debian/sbt-0.13.5.deb && \
    dpkg -i sbt-0.13.5.deb

COPY . /marathon
WORKDIR /marathon

RUN sbt assembly && \
    mv $(find target -name 'marathon-assembly-*.jar' | sort | tail -1) ./ && \
    rm -rf target/* ~/.sbt ~/.ivy2 && \
    mv marathon-assembly-*.jar target

ENTRYPOINT ["./bin/start"]
