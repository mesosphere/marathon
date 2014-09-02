# [Marathon](https://mesosphere.github.io/marathon/) [![Build Status](https://travis-ci.org/mesosphere/marathon.png?branch=master)](https://travis-ci.org/mesosphere/marathon)

Marathon is an [Apache Mesos][Mesos] framework for long-running applications. Given that
you have Mesos running as the kernel for your datacenter, Marathon is the
[`init`][init] or [`upstart`][upstart] daemon.

Marathon provides a
[REST API](https://mesosphere.github.io/marathon/docs/rest-api.html) for
starting, stopping, and scaling applications. Marathon is written in Scala and
can run in highly-available mode by running multiple copies of Marathon. The
state of running tasks gets stored in the Mesos state abstraction.

Try Marathon now on [Elastic Mesos](http://elastic.mesosphere.io) and learn how
to use it in Mesosphere's interactive
[Marathon tutorial](http://mesosphere.io/learn/run-services-with-marathon/)
that can be personalized for your cluster.

Marathon is a *meta framework*: you can start other Mesos frameworks such as
Chronos or [Storm][Storm] with it to ensure they survive machine failures.
It can launch anything that can be launched in a standard shell. In fact, you
can even start other Marathon instances via Marathon.

Details for running the full Mesosphere stack with Mesos + Marathon are
available via the [Mesosphere Website](http://mesosphere.io/docs/).

## Features

* *HA* -- run any number of Marathon schedulers, but only one gets elected as
    leader; if you access a non-leader, your request gets proxied to the
    current leader
* *[Constraints](https://mesosphere.github.io/marathon/docs/constraints.html)* - e.g., only one instance of an application per rack, node, etc.
* *[Service Discovery &amp; Load Balancing](https://mesosphere.github.io/marathon/docs/service-discovery-load-balancing.html)* via HAProxy or the events API (see below).
* *[Health Checks](https://mesosphere.github.io/marathon/docs/health-checks.html)*: check your application's health via HTTP or TCP checks.
* *[Event Subscription](https://mesosphere.github.io/marathon/docs/rest-api.html#event-subscriptions)* lets you supply an HTTP endpoint to receive notifications, for example to integrate with an external load balancer.
* *Web UI*
* *[JSON/REST API](https://mesosphere.github.io/marathon/docs/rest-api.html)* for easy integration and scriptability
* *Basic Auth* and *SSL*
* *Metrics*: available at `/metrics` in JSON format

## Setting Up And Running Marathon

### Requirements

* [Mesos][Mesos] 0.15.0+
* [ZooKeeper][Zookeeper]
* JDK 1.6+
* Scala 2.10+
* sbt 0.13.5

### Upgrading to a newer version

Upgrading to a newer version of Marathon should be seamless. Be aware that
downgrading from versions >= 0.7.0 to a version < 0.7.0 is not possible
because of incompatible changes in the data format. We recommend to create
backups of the Zookeeper state before upgrading to be able to downgrade in case
of problems after an upgrade.

### Installation

1.  Install [Mesos][Mesos]. One easy way is via your system's package manager.
    Current builds for major Linux distributions and Mac OS X are available
    from on the Mesosphere [downloads page](http://mesosphere.io/downloads/).

    If building from source, see the
    Mesos [Getting Started](http://mesos.apache.org/gettingstarted/) page or the
    [Mesosphere tutorial](http://mesosphere.io/2013/08/01/distributed-fault-tolerant-framework-apache-mesos/)
    for details. Running `make install` will install Mesos in `/usr/local` in
    the same way as these packages do.

1.  Download and unpack the latest release.

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
[ZooKeeper][ZooKeeper] and Mesos running. The following command launches
Marathon on Mesos in *production mode*. Point your web browser to
`localhost:8080` and you should see the Marathon UI.

    ./bin/start --master zk://zk1.foo.bar:2181,zk2.foo.bar:2181/mesos --zk zk://zk1.foo.bar:2181,zk2.foo.bar:2181/marathon

Marathon uses `--master` to find the Mesos masters, and `--zk` to find ZooKeepers
for storing state. They are separate options because Mesos masters can be
discovered in other ways as well.

### Running in Development Mode

Mesos local mode allows you to run Marathon without launching a full Mesos
cluster. It is meant for experimentation and not recommended for production
use. Note that you still need to run ZooKeeper for storing state. The following
command launches Marathon on Mesos in *local mode*. Point your web browser to
`http://localhost:8080`, and you should see the Marathon UI.

    ./bin/start --master local --zk zk://localhost:2181/marathon

### Running with a standalone Mesos master

The released version 0.19.0 of Mesos does not allow frameworks to launch an in-process master. This will be fixed in the next release. In the meantime, you can still run Marathon locally if you launch a master in a separate console and either point Marathon directly at the master itself or at the same Zookeeper (if you specified this when launching the master):

    ./bin/start --master zk://localhost:2181/mesos --zk zk://localhost:2181/marathon
    ./bin/start --master localhost:5050 --zk zk://localhost:2181/marathon

### Configuration Options

* `MESOS_NATIVE_JAVA_LIBRARY`: `bin/start` searches the common installation paths,
    `/usr/lib` and `/usr/local/lib`, for the Mesos native library. If the
    library lives elsewhere in your configuration, set the environment variable
    `MESOS_NATIVE_JAVA_LIBRARY` to its full path.

  For example:

      MESOS_NATIVE_JAVA_LIBRARY=/Users/bob/libmesos.dylib ./bin/start --master local --zk zk://localhost:2181/marathon

Run `./bin/start --help` for a full list of configuration options.

## REST API Usage

The full [API documentation](https://mesosphere.github.io/marathon/docs/rest-api.html) shows details about everything the
Marathon API can do.

### Example using the V2 API

    # Start an app with 128 MB memory, 1 CPU, and 1 instance
    curl -X POST -H "Accept: application/json" -H "Content-Type: application/json" \
        localhost:8080/v2/apps \
        -d '{"id": "app-123", "cmd": "sleep 600", "instances": 1, "mem": 128, "cpus": 1}'

    # Scale the app to 2 instances
    curl -X PUT -H "Accept: application/json" -H "Content-Type: application/json" \
        localhost:8080/v2/apps/app-123 \
        -d '{"id": "app-123", "cmd": "sleep 600", "instances": 2, "mem": 128, "cpus": 1}'

    # Stop the app
    curl -X DELETE localhost:8080/v2/apps/app-123

##### Example starting an app using constraints

    # Start an app with a hostname uniqueness constraint
    curl -X POST -H "Accept: application/json" -H "Content-Type: application/json" \
        localhost:8080/v2/apps \
        -d '{"id": "constraints", "cmd": "hostname && sleep 600", "instances": 10, "mem": 64, "cpus": 0.1, "constraints": [["hostname", "UNIQUE", ""]]}'

## Marathon Clients

* [Ruby gem and command line client](https://rubygems.org/gems/marathon_client)

    Running Chronos with the Ruby Marathon Client:

        marathon start -i chronos -u https://s3.amazonaws.com/mesosphere-binaries-public/chronos/chronos.tgz \
            -C "./chronos/bin/demo ./chronos/config/nomail.yml \
            ./chronos/target/chronos-1.0-SNAPSHOT.jar" -c 1.0 -m 1024 -H http://foo.bar:8080
* [Scala client](https://github.com/guidewire/marathon-client), developed at Guidewire
* [Java client](https://github.com/mohitsoni/marathon-client) by Mohit Soni
* [Python client](https://github.com/thefactory/marathon-python), developed at [The Factory](http://www.thefactory.com)
* [Python client](https://github.com/Wizcorp/marathon-client.py), developed at [Wizcorp](http://www.wizcorp.jp)
* [Go client](https://github.com/jbdalido/gomarathon) by Jean-Baptiste Dalido
* [Node client](https://github.com/silas/node-mesos) by Silas Sewell

## Companies using Marathon

* [Airbnb](https://www.airbnb.com/)
* [eBay](http://www.ebay.com/)
* [The Factory](https://github.com/thefactory/)
* [Guidewire](http://www.guidewire.com/)
* [OpenTable](http://www.opentable.com/)
* [PayPal](https://www.paypal.com)
* [Sailthru](http://www.sailthru.com/)
* [Viadeo](http://www.viadeo.com)

Not in the list? Open a pull request and add yourself!

## Help

If you have questions, please post on the
[Marathon Framework Group](https://groups.google.com/forum/?hl=en#!forum/marathon-framework)
email list. You can find Mesos support in the `#mesos` channel on
[freenode][freenode] (IRC). The team at [Mesosphere][Mesosphere] is also happy
to answer any questions.

## Authors

Marathon was created by [Tobias Knaup](https://github.com/guenter) and
[Florian Leibert](https://github.com/florianleibert) and continues to be
developed by the team at Mesosphere and by many contributors from
the community.

[![githalytics.com alpha](https://cruel-carlota.gopagoda.com/678b61f70ab36917caf159d22ba55f76 "githalytics.com")](http://githalytics.com/mesosphere/marathon)

[Chronos]: https://github.com/airbnb/chronos "Airbnb's Chronos"
[Mesos]: https://mesos.apache.org/ "Apache Mesos"
[Zookeeper]: https://zookeeper.apache.org/ "Apache Zookeeper"
[Storm]: http://storm-project.net/ "distributed realtime computation"
[freenode]: https://freenode.net/ "IRC channels"
[upstart]: http://upstart.ubuntu.com/ "Ubuntu's event-based daemons"
[init]: https://en.wikipedia.org/wiki/Init "init"
[Mesosphere]: http://mesosphere.io/ "Mesosphere"
