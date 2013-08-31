# Marathon

Marathon is a Mesos framework for long running services. If Mesos is the kernel for your datacenter, Marathon is the init.d or upstart.
It provides a REST API for starting, stopping, and scaling services. There is also a ruby [command line client](https://github.com/mesosphere/marathon_client).
Marathon is written in Scala and can run in highly available mode by running multiple Marathon instances. The state of tasks is stored in the Mesos state abstraction. Features are listed below.

<p align="center">
  <img src="http://www.jeremyscottadidas-wings.co.uk/images/Adidas-Jeremy-Scott-Wing-Shoes-2-0-Gold-Sneakers.jpg" width="30%" height="30%">
</p>

Marathon is a meta framework - you can start other [Mesos][Mesos] frameworks such as [Chronos][Chronos] or Storm via Marathon. In fact, you can even
start other Marathon instances via Marathon. Marathon was written by the team that also developped [Chronos][Chronos].

Marathon can launch anything that can be launched in a standard shell.

## Help

If you have questions please use [Marathon Framework Group](https://groups.google.com/forum/?hl=en#!forum/marathon-framework).
You can find mesos support in #mesos on freenode (irc). The team at [Mesosphere](https://mesosphe.re) is also happy to answer any qustions.

## Authors

* [Tobias Knaup](https://github.com/guenter)
* [Florian Leibert](https://github.com/florianleibert)
* [Harry Shoff](https://github.com/hshoff)
* [Jason Dusek](https://github.com/solidsnack)

## Requirements

* Mesos
* Zookeeper
* JDK 1.6+
* Scala 2.10+
* Maven 3.0+

## Overview

The following graphic depicts how Marathon can run on top of Mesos together with the Chronos framework.
In this use case, Marathon was the first framework and runs alongside Mesos, meaning the Marathon scheduler does not run
on a Mesos slave. Marathon then starts and runs two instances of the Chronos scheduler (another Mesos framework) as a Marathon task.
Thus, should one of the two Chronos tasks die because the underlying slave crashes or someone unplugs power to the node it's running on,
Marathon would re-start an instance of Chronos on another slave ensuring that always two Chronos processes would run.
Since Chronos itself is a framwork and receives Mesos resource offers once it's launched, it can start tasks on Mesos.
In our use case, Chronos is currently running two tasks, one that dumps the production MySQL database to S3 and another task
that sends the email newsletter to all customers (via Rake). In the meantime, Marathon also runs every service required for our web application.

![architecture](https://raw.github.com/mesosphere/marathon/master/docs/architecture.png "Marathon on mesos")

The next graphic shows a more application centric view of Marathon running three tasks: Search, Jetty and Rails.

![Marathon1](https://raw.github.com/mesosphere/marathon/master/docs/marathon1.png "Initial Marathon")

After the website gets a lot of traction and our user base grows, we decide to scale-up the search and rails services.

![Marathon2](https://raw.github.com/mesosphere/marathon/master/docs/marathon2.png "Scaled Marathon")

Unfortunately, one of the datacenter workers tripped over a power cord and one machine was unplugged. No problem for Marathon,
it moves the affected search service and rails instance to a node that has spare capacity. The engineer is embarrased
but Marathon saved him.

![Marathon3](https://raw.github.com/mesosphere/marathon/master/docs/marathon3.png "Marathon Recovering a service")

## Features

* HA - you can run any number of marathon schedulers and only one is elected as leader. If you access a non-leader you will get an HTTP redirect to the current leader.
* Basic Auth & SSL
* REST API
* Service constraints (e.g. only one instance of a service per rack, node, etc.)
* Metrics (via Coda Hale's metrics library)
* Event subscription (e.g. if you need to notify an external service of task updates or state changes you can supply a HTTP endpoint which will receive these notifications. This is especially useful if you have 
* Web UI

## API

Using [HTTPie](http://httpie.org):

    http localhost:8080/v1/apps/start id=sleep cmd='sleep 600' instances=1 mem=128 cpus=1
    http localhost:8080/v1/apps/scale id=sleep instances=2
    http localhost:8080/v1/apps/stop id=sleep

Using [Marthon Client](https://github.com/mesosphere/marathon_client):

    marathon start -i chronos -u https://s3.amazonaws.com/mesosphere-binaries-public/chronos/chronos.tgz -C "./chronos/bin/demo ./chronos/config/nomail.yml ./chronos/target/chronos-1.0-SNAPSHOT.jar" -c 1.0 -m 1024 -H http://foo.bar:8080

More in the examples dir.



[Chronos]: https://raw.github.com/airbnb/chronos "Airbnb's Chronos"
[Mesos]: http://incubator.apache.org/mesos/ "Apache Mesos"
