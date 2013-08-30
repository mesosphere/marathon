# Marathon

Marathon is a Mesos framework for long running services. If Mesos is the kernel for your datacenter, Marathon is the init.d or upstart.
It provides a REST API for starting, stopping, and scaling services. There is also a ruby [command line client](https://github.com/mesosphere/marathon_client).
Marathon is written in Scala and runs HA. The state of tasks is stored in the Mesos state abstraction. Features are listed below.

<p align="center">
  <img src="http://www.jeremyscottadidas-wings.co.uk/images/Adidas-Jeremy-Scott-Wing-Shoes-2-0-Gold-Sneakers.jpg" width="30%" height="30%">
</p>

Marathon is a meta framework - you can start other [Mesos][Mesos] frameworks such as [Chronos][Chronos] or Storm via Marathon. In fact, you can even
start other Marathon instances via Marathon. Marathon was written by the team that also wrote [Chronos][Chronos].

Marathon can launch anything that can be launched in a standard shell.

## Authors

* Tobias "Tobi" Knaup
* Florian "Flo" Leibert
* Harry Shoff
* Jason Dusek

## Help

If you have questions please use [Marathon Framework Group](https://groups.google.com/forum/?hl=en#!forum/marathon-framework).
You can find mesos support in #mesos on freenode (irc). The team at [Mesosphere](https://mesosphe.re) is also happy to answer any qustions.

## Requirements

* Mesos
* Zookeeper

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
