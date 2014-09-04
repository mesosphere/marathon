# [Marathon](https://mesosphere.github.io/marathon/) [![Build Status](https://travis-ci.org/mesosphere/marathon.png?branch=master)](https://travis-ci.org/mesosphere/marathon)

Marathon is an [Apache Mesos][Mesos] framework for long-running applications. Given that
you have Mesos running as the kernel for your datacenter, Marathon is the
[`init`][init] or [`upstart`][upstart] daemon.

Marathon provides a
[REST API](https://mesosphere.github.io/marathon/docs/rest-api.html) for
starting, stopping, and scaling applications. Marathon is written in Scala and
can run in highly-available mode by running multiple copies. The
state of running tasks gets stored in the Mesos state abstraction.

Try Marathon now on AWS with [Elastic Mesos](http://elastic.mesosphere.io) or with [Mesosphere for Google Compute Platform](http://google.mesosphere.io) and learn how
to use it in Mesosphere's interactive
[Marathon tutorial](http://mesosphere.io/learn/run-services-with-marathon/)
that can be personalized for your cluster.

Marathon is a *meta framework*: you can start other Mesos frameworks such as
Chronos or [Storm][Storm] with it to ensure they survive machine failures.
It can launch anything that can be launched in a standard shell. In fact, you
can even start other Marathon instances via Marathon.

Using Marathon versions 0.7.0+ and Mesos 0.20.0+, you can [deploy, run and scale Docker containers](https://mesosphere.github.io/marathon/docs/native-docker.html) with ease.

Documentation for installing and configuring the full Mesosphere stack with Mesos + Marathon is
available on the [Mesosphere website](http://mesosphere.io/docs/).

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

### Installation

#### Install Mesos

Marathon requires Mesos installed on the same machine in order to use a shared library.
One easy way is via your system's package manager.
Current builds for major Linux distributions are available
from on the Mesosphere [downloads page](http://mesosphere.io/downloads/)
or from Mesosphere's [repositories](http://mesosphere.io/2014/07/17/mesosphere-package-repositories/).

If building from source, see the
Mesos [Getting Started](http://mesos.apache.org/gettingstarted/) page or the
[Mesosphere tutorial](http://mesosphere.io/2013/08/01/distributed-fault-tolerant-framework-apache-mesos/)
for details. Running `make install` will install Mesos in `/usr/local` in
the same way as these packages do.

#### Install Marathon

Full instructions on how to install prepackaged releases are available [in the Marathon docs](https://mesosphere.github.io/marathon/docs/). Alternatively, you can build Marathon from source.

##### Building From Source

1.  To build Marathon from source, check out this repo and use sbt to build a JAR:

        git clone https://github.com/mesosphere/marathon.git
        cd marathon
        sbt assembly

1.  Run `./bin/build-distribution` to package Marathon as an
    [executable JAR](http://mesosphere.io/2013/12/07/executable-jars/)
    (optional).

### Running in Development Mode

Mesos local mode allows you to run Marathon without launching a full Mesos
cluster. It is meant for experimentation and not recommended for production
use. Note that you still need to run ZooKeeper for storing state. The following
command launches Marathon on Mesos in *local mode*. Point your web browser to
`http://localhost:8080`, and you should see the Marathon UI.

    ./bin/start --master local --zk zk://localhost:2181/marathon

### Running in Production Mode

The following command launches Marathon on Mesos in *production mode*. Point your web browser to
`localhost:8080` and you should see the Marathon UI.

    ./bin/start --master zk://zk1.foo.bar:2181,zk2.foo.bar:2181/mesos --zk zk://zk1.foo.bar:2181,zk2.foo.bar:2181/marathon

Marathon uses `--master` to find the Mesos masters, and `--zk` to find ZooKeepers
for storing state. These are separate options because Mesos masters can be
discovered in other ways as well.

For more information on how to run Marathon in production and configuration options, see [the Marathon docs](https://mesosphere.github.io/marathon/docs/).

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
