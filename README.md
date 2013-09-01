# Marathon

Marathon is a [Mesos][Mesos] framework for long-running services.
Given that you have [Mesos][Mesos] running as the kernel for your datacenter, 
Marathon is the `init.d` or [upstart][upstart] daemon.
It was written by the team that also developed [Chronos][Chronos].

Marathon provides a REST API for starting, stopping, and scaling services.
There is also a Ruby [command line client](https://github.com/mesosphere/marathon_client).
Marathon is written in Scala and can run in highly-available mode by running multiple Marathon instances.
The state of running tasks gets stored in the [Mesos][Mesos] state abstraction.
Features are listed below.

<p align="center">
  <img src="http://www.jeremyscottadidas-wings.co.uk/images/Adidas-Jeremy-Scott-Wing-Shoes-2-0-Gold-Sneakers.jpg" width="30%" height="30%">
</p>

Marathon is a *meta framework*:
you can start other [Mesos][Mesos] frameworks such as [Chronos][Chronos] or [Storm][Storm] via Marathon.
Marathon can launch anything that can be launched in a standard shell.
In fact, you can even start other Marathon instances via Marathon.

## Help

If you have questions, please post on the [Marathon Framework Group](https://groups.google.com/forum/?hl=en#!forum/marathon-framework) email list.
You can find [Mesos][Mesos] support in the `#mesos` channel on [freenode][freenode] (IRC).
The team at [Mesosphere](https://mesosphe.re) is also happy to answer any questions.

## Authors

* [Tobias Knaup](https://github.com/guenter)
* [Florian Leibert](https://github.com/florianleibert)
* [Harry Shoff](https://github.com/hshoff)
* [Jason Dusek](https://github.com/solidsnack)

## Requirements

* [Mesos][Mesos] 0.14+ (see `pom.xml`)
* [Zookeeper][Zookeeper]
* JDK 1.6+
* Scala 2.10+
* Maven 3.0+

## Overview

The graphic shown below depicts how Marathon runs on top of [Mesos][Mesos] together with the [Chronos][Chronos] framework.
In this case, Marathon is the first framework to be launched and it runs alongside [Mesos][Mesos].
In other words, the Marathon scheduler does not run on a [Mesos][Mesos] slave -- it runs on a Master, orthogonally to [Mesos][Mesos].
Marathon launches two instances of the [Chronos][Chronos] scheduler as a Marathon task.
If either of the two [Chronos][Chronos] tasks dies -- due to underlying slave crashes, power loss in the cluster, etc. --
Marathon will re-start an [Chronos][Chronos] instance on another slave.
This approach ensures that two [Chronos][Chronos] processes are always running.

Since [Chronos][Chronos] itself is a framwork and receives [Mesos][Mesos] resource offers, it can start tasks on [Mesos][Mesos].
In the use case shown below, [Chronos][Chronos] is currently running two tasks.
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

## Features

* *HA* -- run any number of Marathon schedulers, but only one gets elected as leader; if you access a non-leader, you get an HTTP redirect to the current leader
* *Basic Auth* and *SSL*
* *REST API*
* *Web UI*
* *Metrics* -- via Coda Hale's [metrics library](http://metrics.codahale.com/)
* *Service Constraints* -- e.g., only one instance of a service per rack, node, etc.
* *Service Discovery* and *Monitoring*
* *Event Subscription* -- e.g., if you need to notify an external service about task updates or state changes, you can supply an HTTP endpoint to receive notifications


## Set-up and Running

First, use Maven to build the package:

    mvn package

### Local Mode

The following command launches Marathon on [Mesos][Mesos] in *local mode*, which is not how you would run it on your cluster.
Point your web browser to `localhost:8080` and you should see the Marathon UI.

    bin/start --master local

### Production Mode

To launch Marathon in *production mode*, you need to have both [Zookeeper][Zookeeper] and [Mesos][Mesos] running:
 
    bin/start --master zk://zk1.foo.bar,zk2.foo.bar/mesos

## API

Using [HTTPie][HTTPie]:

    http localhost:8080/v1/apps/start id=sleep cmd='sleep 600' instances=1 mem=128 cpus=1
    http localhost:8080/v1/apps/scale id=sleep instances=2
    http localhost:8080/v1/apps/stop id=sleep

Using [Marthon Client](https://github.com/mesosphere/marathon_client):

    marathon start -i chronos -u https://s3.amazonaws.com/mesosphere-binaries-public/chronos/chronos.tgz -C "./chronos/bin/demo ./chronos/config/nomail.yml ./chronos/target/chronos-1.0-SNAPSHOT.jar" -c 1.0 -m 1024 -H http://foo.bar:8080

See more example API uses in the `examples` directory.


[Chronos]: https://raw.github.com/airbnb/chronos "Airbnb's Chronos"
[HTTPie]: http://httpie.org "a CLI, cURL-like tool for humans"
[Mesos]: http://incubator.apache.org/mesos/ "Apache Mesos"
[Zookeeper]: http://zookeeper.apache.org/ "Apache Zookeeper"
[Storm]: http://storm-project.net/ "distributed realtime computation"
[freenode]: http://freenode.net/ "IRC channels"
[upstart]: http://upstart.ubuntu.com/ "Ubuntu's event-based daemons"
