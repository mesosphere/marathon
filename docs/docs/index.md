---
---

## Setting Up And Running Marathon

### Requirements

* [Apache Mesos][Mesos] 0.15.0+
* [Apache ZooKeeper][ZooKeeper]
* JDK 1.6+
* Scala 2.10+
* sbt 0.13.5

### Installation

1.  Install Mesos.

    One easy way is via your system's package manager.
    Current builds for major Linux distributions and Mac OS X are available
    from on the Mesosphere [downloads page](http://mesosphere.io/downloads/).

    If building from source, see the
    Mesos [Getting Started](http://mesos.apache.org/gettingstarted/) page or the
    [Mesosphere tutorial](http://mesosphere.io/2013/08/01/distributed-fault-tolerant-framework-apache-mesos/)
    for details. Running `make install` will install Mesos in `/usr/local` in
    the same way as these packages do.

1.  Download and unpack the latest Marathon release.

    **For Mesos 0.19.0:**

        curl -O http://downloads.mesosphere.io/marathon/marathon-0.6.1/marathon-0.6.1.tgz
        tar xzf marathon-0.6.1.tgz

    **For Mesos 0.17.0 to 0.18.2:**

        curl -O http://downloads.mesosphere.io/marathon/marathon-0.5.1/marathon-0.5.1.tgz
        tar xzf marathon-0.5.1.tgz

    **For Mesos 0.16.0 and earlier:**

        curl -O http://downloads.mesosphere.io/marathon/marathon-0.5.1_mesos-0.16.0/marathon-0.5.1_mesos-0.16.0.tgz
        tar xzf marathon-0.5.1_mesos-0.16.0.tgz

    SHA-256 checksums are available by appending `.sha256` to the URLs.


#### Building From Source

1.  To build Marathon from source, check out this repo and use sbt to build a JAR:

        git clone https://github.com/mesosphere/marathon.git
        cd marathon
        sbt assembly

1.  Run `./bin/build-distribution` to package Marathon as an
    [executable JAR](http://mesosphere.io/2013/12/07/executable-jars/)
    (optional).


### Running in Production Mode

To launch Marathon in *production mode*, you need to have both
ZooKeeper and Mesos running. The following command launches
Marathon in *production mode*. Point your web browser to
`localhost:8080` and you should see the Marathon UI.

    ./bin/start --master zk://zk1.foo.bar:2181,zk2.foo.bar:2181/mesos --zk zk://zk1.foo.bar:2181,zk2.foo.bar:2181/marathon

Marathon uses `--master` to find the Mesos masters, and `--zk` to find ZooKeepers
for storing state. They are separate options because Mesos masters can be
discovered in other ways as well.

For all configuration options, see the [command line flags](command-line-flags.html) doc.

### Running in Development Mode

Local mode allows you to run Marathon without launching a full Mesos
cluster. It is meant for experimentation and not recommended for production
use. Note that you still need to run ZooKeeper for storing state. The following
command launches Marathon in *local mode*. Point your web browser to
`http://localhost:8080`, and you should see the Marathon UI.

    ./bin/start --master local --zk zk://localhost:2181/marathon

### Running with a standalone Mesos master

The released version 0.19.0 of Mesos does not allow frameworks to launch an in-process master.
You can still run Marathon locally if you launch a master in a separate console
and either point Marathon directly at the master itself or at the same ZooKeeper
(if you specified this when launching the master):

    ./bin/start --master zk://localhost:2181/mesos --zk zk://localhost:2181/marathon
    ./bin/start --master localhost:5050 --zk zk://localhost:2181/marathon

[Mesos]: https://mesos.apache.org/ "Apache Mesos"
[Zookeeper]: https://zookeeper.apache.org/ "Apache ZooKeeper"
