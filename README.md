# Marathon [![Build Status](https://travis-ci.org/mesosphere/marathon.png?branch=master)](https://travis-ci.org/mesosphere/marathon)

Marathon is an [Apache Mesos][Mesos] framework for long-running applications. Given that
you have Mesos running as the kernel for your datacenter, Marathon is the
[`init`][init] or [`upstart`][upstart] daemon.

Marathon provides a
[REST API](https://github.com/mesosphere/marathon/blob/master/REST.md) for
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

## Features

* *HA* -- run any number of Marathon schedulers, but only one gets elected as
    leader; if you access a non-leader, your request gets proxied to the
    current leader
* *[Constraints](https://github.com/mesosphere/marathon/wiki/Constraints)* - e.g., only one instance of an application per rack, node, etc.
* *[Service Discovery &amp; Load Balancing](https://github.com/mesosphere/marathon/wiki/Service-Discovery-&-Load-Balancing)* via HAProxy or the events API (see below).
* *[Health Checks](https://github.com/mesosphere/marathon/wiki/Health-Checks)*: check your application's health via HTTP or TCP checks.
* *[Event Subscription](https://github.com/mesosphere/marathon/wiki/Event-Bus)* lets you supply an HTTP endpoint to receive notifications, for example to integrate with an external load balancer.
* *Web UI*
* *JSON/REST API* for easy integration and scriptability
* *Basic Auth* and *SSL*
* *Metrics*: available at `/metrics` in JSON format

## Overview

The graphic shown below depicts how Marathon runs on top of Mesos together with
the Chronos framework. In this case, Marathon is the first framework to be
launched and it runs alongside Mesos. In other words, the Marathon scheduler
processes were started outside of Mesos using `init`, `upstart`, or a similar
tool. Marathon launches two instances of the Chronos scheduler as a Marathon
task. If either of the two Chronos tasks dies -- due to underlying slave
crashes, power loss in the cluster, etc. -- Marathon will re-start a Chronos
instance on another slave. This approach ensures that two Chronos processes are
always running.

Since Chronos itself is a framework and receives Mesos resource offers, it can
start tasks on Mesos. In the use case shown below, Chronos is currently running
two tasks. One dumps a production MySQL database to S3, while another sends an
email newsletter to all customers via Rake. Meanwhile, Marathon also runs the
other applications that make up our website, such as JBoss servers, a Jetty
service, Sinatra, Rails, and so on.

![architecture](https://raw.github.com/mesosphere/marathon/master/docs/img/architecture.png "Marathon on Mesos")

The next graphic shows a more application-centric view of Marathon running
three applications, each with a different number of tasks: Search (1), Jetty
(3), and Rails (5).

![Marathon1](https://raw.github.com/mesosphere/marathon/master/docs/img/marathon1.png "Initial Marathon")

As the website gains traction and the user base grows, we decide to scale-out
the search service and our Rails-based application. This is done via a
REST call to the Marathon API to add more tasks. Marathon will take care of
placing the new tasks on machines with spare capacity, honoring the
constraints we previously set.

![Marathon2](https://raw.github.com/mesosphere/marathon/master/docs/img/marathon2.png "Marathon scale-out")

Imagine that one of the datacenter workers trips over a power cord and a server
gets unplugged. No problem for Marathon, it moves the affected search service
and Rails tasks to a node that has spare capacity. The engineer may be
temporarily embarrased, but Marathon saves him from having to explain a
difficult situation!

![Marathon3](https://raw.github.com/mesosphere/marathon/master/docs/img/marathon3.png "Marathon recovering an application")

## Setting Up And Running Marathon

### Requirements

* [Mesos][Mesos] 0.15.0+
* [Zookeeper][Zookeeper]
* JDK 1.6+
* Scala 2.10+
* sbt 0.13.5

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
[Zookeeper][Zookeeper] and Mesos running. The following command launches
Marathon on Mesos in *production mode*. Point your web browser to
`localhost:8080` and you should see the Marathon UI.

    ./bin/start --master zk://zk1.foo.bar:2181,zk2.foo.bar:2181/mesos --zk zk://zk1.foo.bar:2181,zk2.foo.bar:2181/marathon

Marathon uses `--master` to find the Mesos masters, and `--zk` to find Zookeepers
for storing state. They are separate options because Mesos masters can be
discovered in other ways as well.

### Running in Development Mode

Mesos local mode allows you to run Marathon without launching a full Mesos
cluster. It is meant for experimentation and not recommended for production
use. Note that you still need to run Zookeeper for storing state. The following
command launches Marathon on Mesos in *local mode*. Point your web browser to
`http://localhost:8080`, and you should see the Marathon UI.

    ./bin/start --master local --zk zk://localhost:2181/marathon

### Command Line Options

The following options can influence how Marathon works:

* `--master`: The URL of the Mesos master. The format is a comma-delimited list of
    of hosts like `zk://host1:port,host2:port/mesos`. Pay particular attention to the
    leading `zk://` and trailing `/mesos`!
* `--failover_timeout`: The failover_timeout for mesos in seconds (default: 1 week)
* `--ha`: Runs Marathon in HA mode with leader election. Allows starting an arbitrary
    number of other Marathons but all need to be started in HA mode. This mode
    requires a running ZooKeeper. See `--master`.
* `--checkpoint`: Enable checkpointing of tasks. Requires checkpointing enabled on
    slaves. Allows tasks to continue running during mesos-slave restarts and upgrades.
* `--local_port_min`: Min port number to use when assigning ports to apps.
* `--local_port_max`: Max port number to use when assigning ports to apps.
* `--executor`: Executor to use when none is specified.
* `--hostname`: The advertised hostname stored in ZooKeeper so another standby host
    can redirect to the elected leader.
* `--mesos_role`: Mesos role for this framework.
* `--task_launch_timeout`: Time, in milliseconds, to wait for a task to enter the
    TASK_RUNNING state before killing it.
* `--task_rate_limit`: This is the time window within which instances may be launched
    for a given app.  For example, if an app has 5 instances, it will only launch 5
    instances within 60s regardless of whether they succeed or fail.
* `--reconciliation_initial_delay`: This is the length of time, in milliseconds, before
    Marathon begins to periodically perform task reconciliation operations.
* `--mesos_user`: Mesos user for this framework. Defaults to current user.

### Configuration Options

* `MESOS_NATIVE_LIBRARY`: `bin/start` searches the common installation paths,
    `/usr/lib` and `/usr/local/lib`, for the Mesos native library. If the
    library lives elsewhere in your configuration, set the environment variable
    `MESOS_NATIVE_LIBRARY` to its full path.

  For example:

      MESOS_NATIVE_LIBRARY=/Users/bob/libmesos.dylib ./bin/start --master local --zk zk://localhost:2181/marathon

Run `./bin/start --help` for a full list of configuration options.

## REST API Usage

The full [API documentation](REST.md) shows details about everything the
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

### The V1 API (Deprecated)

The V1 API was deprecated in Marathon v0.4.0 on 2014-01-24 but continues to work
as it did before being deprecated. Details on the V1 API can be found in the
[API documentation](REST.md#api-version-1-deprecated).

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

## Companies using Marathon

* [Airbnb](https://www.airbnb.com/)
* [eBay](http://www.ebay.com/)
* [Guidewire](http://www.guidewire.com/)
* [OpenTable](http://www.opentable.com/)
* [PayPal](https://www.paypal.com)
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

[![githalytics.com alpha](https://cruel-carlota.pagodabox.com/678b61f70ab36917caf159d22ba55f76 "githalytics.com")](http://githalytics.com/mesosphere/marathon)

[Chronos]: https://github.com/airbnb/chronos "Airbnb's Chronos"
[Mesos]: https://mesos.apache.org/ "Apache Mesos"
[Zookeeper]: https://zookeeper.apache.org/ "Apache Zookeeper"
[Storm]: http://storm-project.net/ "distributed realtime computation"
[freenode]: https://freenode.net/ "IRC channels"
[upstart]: http://upstart.ubuntu.com/ "Ubuntu's event-based daemons"
[init]: https://en.wikipedia.org/wiki/Init "init"
[Mesosphere]: http://mesosphere.io/ "Mesosphere"
