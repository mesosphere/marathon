# Marathon [![Build Status](https://travis-ci.org/mesosphere/marathon.png?branch=master)](https://travis-ci.org/mesosphere/marathon)

Marathon is a [Mesos][Mesos] framework for long-running services.
Given that you have Mesos running as the kernel for your datacenter, 
Marathon is the `init` or [upstart][upstart] daemon.

Marathon provides a REST API for starting, stopping, and scaling services.
Marathon is written in Scala and can run in highly-available mode by running multiple Marathon instances.
The state of running tasks gets stored in the Mesos state abstraction.

Try Marathon now on [Elastic Mesos](http://elastic.mesosphere.io).

Go to the [interactive Marathon tutorial](http://mesosphere.io/learn/run-services-with-marathon/) that can be personalized for your cluster.

Marathon is a *meta framework*:
you can start other Mesos frameworks such as Chronos or [Storm][Storm].
It can launch anything that can be launched in a standard shell.
In fact, you can even start other Marathon instances via Marathon.

## Features

* *HA* -- run any number of Marathon schedulers, but only one gets elected as leader; if you access a non-leader, you get an HTTP redirect to the current leader
* *Basic Auth* and *SSL*
* *REST API*
* *Web UI*
* *Metrics* -- via Coda Hale's [metrics library](http://metrics.codahale.com/)
* *Service Constraints* -- e.g., only one instance of a service per rack, node, etc.
* *Service Discovery* and *Monitoring*
* *Event Subscription* -- e.g., if you need to notify an external service about task updates or state changes, you can supply an HTTP endpoint to receive notifications

## Overview

The graphic shown below depicts how Marathon runs on top of Mesos together with the Chronos framework.
In this case, Marathon is the first framework to be launched and it runs alongside Mesos.
In other words, the Marathon scheduler processes were started outside of Mesos using `init`, `upstart`, or a similar tool.
Marathon launches two instances of the Chronos scheduler as a Marathon task.
If either of the two Chronos tasks dies -- due to underlying slave crashes, power loss in the cluster, etc. --
Marathon will re-start an Chronos instance on another slave.
This approach ensures that two Chronos processes are always running.

Since Chronos itself is a framework and receives Mesos resource offers, it can start tasks on Mesos.
In the use case shown below, Chronos is currently running two tasks.
One dumps a production MySQL database to S3, while another sends an email newsletter to all customers via Rake.
Meanwhile, Marathon also runs the services required for the web app, in general.

![architecture](https://raw.github.com/mesosphere/marathon/master/docs/architecture.png "Marathon on Mesos")

The next graphic shows a more application-centric view of Marathon running three tasks: Search, Jetty, and Rails.

![Marathon1](https://raw.github.com/mesosphere/marathon/master/docs/marathon1.png "Initial Marathon")

As the website gains traction and the user base grows, we decide to scale-out the search and Rails-based services.

![Marathon2](https://raw.github.com/mesosphere/marathon/master/docs/marathon2.png "Marathon scale-out")

Imagine that one of the datacenter workers trips over a power cord and a server gets unplugged.
No problem for Marathon, it moves the affected search service and Rails instance to a node that has spare capacity.
The engineer may be temporarily embarrased, but Marathon saves him from having to explain a difficult situation!

![Marathon3](https://raw.github.com/mesosphere/marathon/master/docs/marathon3.png "Marathon recovering a service")

## Setting Up And Running Marathon

### Requirements

* [Mesos][Mesos] 0.14.0+
* [Zookeeper][Zookeeper]
* JDK 1.6+
* Scala 2.10+
* Maven 3.0+

### Installation

1. Install [Mesos][Mesos]. One easy way is via your system's package manager.
    Current builds for major Linux distributions and Mac OS X are available from Mesosphere on their [downloads page](http://mesosphere.io/downloads/).

    If building from source, see the [Getting Started](http://mesos.apache.org/gettingstarted/) page
    or the [Mesosphere tutorial](http://mesosphere.io/2013/08/01/distributed-fault-tolerant-framework-apache-mesos/)
    for details. Running `make install` will install Mesos in `/usr/local` in the same way as these packages do.

2. Check out Marathon and use Maven to build a JAR:

        mvn package

3. Run `./bin/build-distribution` to package Marathon as an [executable JAR](http://mesosphere.io/2013/12/07/executable-jars/) (optional).

### Running in Production Mode

To launch Marathon in *production mode*, you need to have both [Zookeeper][Zookeeper] and Mesos running.
The following command launches Marathon on Mesos in *production mode*.
Point your web browser to `localhost:8080` and you should see the Marathon UI.

    ./bin/start --master zk://zk1.foo.bar/mesos,zk2.foo.bar/mesos --zk_hosts zk1.foo.bar:2181,zk2.foo.bar:2181

Note the different format of the `--master` and `--zk_hosts` options. Marathon uses `--master` to find the Mesos masters, and `--zk_hosts` to find Zookeepers for storing state. They are separate options because Mesos masters can be discovered in other ways as well.

### Running in Development Mode

Mesos local mode allows you to run Marathon without launching a full Mesos cluster.
It is meant for experimentation and not recommended for production use. Note that you still need to run Zookeeper for storing state.
The following command launches Marathon on Mesos in *local mode*.
Point your web browser to `http://localhost:8080`, and you should see the Marathon UI.

    ./bin/start --master local --zk_hosts localhost:2181
    
#### Working on assets

When editing assets like CSS and JavaScript locally, they are loaded from the packaged
JAR by default and are not editable. To load them from a directory for easy editing,
set the `assets_path` flag when running Marathon:

    ./bin/start --master local --zk_hosts localhost:2181 --assets_path src/main/resources/assets/

### Configuration Options

* `MESOS_NATIVE_LIBRARY`: `bin/start` searches the common installation paths, `/usr/lib`
  and `/usr/local/lib`, for the Mesos native library. If the library lives elsewhere in
  your configuration, set the environment variable `MESOS_NATIVE_LIBRARY` to its full path.

  For example:

      MESOS_NATIVE_LIBRARY=/Users/bob/libmesos.dylib ./bin/start --master local --zk_hosts localhost:2181

Run `./bin/start --help` for a full list of configuration options.

## Example API Usage

Using [HTTPie][HTTPie]:

    http POST localhost:8080/v1/apps/start id=sleep cmd='sleep 600' instances=1 mem=128 cpus=1
    http POST localhost:8080/v1/apps/scale id=sleep instances=2
    http POST localhost:8080/v1/apps/stop id=sleep

Using HTTPie with constraints:

    http POST localhost:8080/v1/apps/start id=constraints cmd='hostname && sleep 600' \
        instances=100 mem=64 cpus=0.1 constraints:='[["hostname", "UNIQUE", ""]]'

Running Chronos with the [Marathon Client](https://github.com/mesosphere/marathon_client):

    marathon start -i chronos -u https://s3.amazonaws.com/mesosphere-binaries-public/chronos/chronos.tgz \
        -C "./chronos/bin/demo ./chronos/config/nomail.yml \
        ./chronos/target/chronos-1.0-SNAPSHOT.jar" -c 1.0 -m 1024 -H http://foo.bar:8080

## Marathon Clients

* [Ruby gem and command line client](https://rubygems.org/gems/marathon_client)
* [Scala client](https://github.com/guidewire/marathon-client), developed at Guidewire

## Help

If you have questions, please post on the [Marathon Framework Group](https://groups.google.com/forum/?hl=en#!forum/marathon-framework) email list.
You can find Mesos support in the `#mesos` channel on [freenode][freenode] (IRC).
The team at [Mesosphere][Mesosphere] is also happy to answer any questions.

## Contributors

Marathon was written by the team at [Mesosphere][Mesosphere] that also developed [Chronos][Chronos], with many contributions from the community.

* [Tobias Knaup](https://github.com/guenter)
* [Ross Allen](https://github.com/ssorallen)
* [Florian Leibert](https://github.com/florianleibert)
* [Harry Shoff](https://github.com/hshoff)
* [Brenden Matthews](https://github.com/brndnmtthws)
* [Jason Dusek](https://github.com/solidsnack)
* [Thomas Rampelberg](https://github.com/pyronicide)

[Chronos]: https://github.com/airbnb/chronos "Airbnb's Chronos"
[HTTPie]: https://github.com/jkbr/httpie "a CLI, cURL-like tool for humans"
[Mesos]: https://mesos.apache.org/ "Apache Mesos"
[Zookeeper]: https://zookeeper.apache.org/ "Apache Zookeeper"
[Storm]: http://storm-project.net/ "distributed realtime computation"
[freenode]: https://freenode.net/ "IRC channels"
[upstart]: http://upstart.ubuntu.com/ "Ubuntu's event-based daemons"
[Mesosphere]: http://mesosphere.io/ "Mesosphere"
