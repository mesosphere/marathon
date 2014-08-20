# Marathon [![Build Status](https://travis-ci.org/mesosphere/marathon.png?branch=master)](https://travis-ci.org/mesosphere/marathon)

__Detailed documentation for Mesos + Marathon available via the [Mesosphere Website](http://mesosphere.io/docs/)__

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

### Command Line Flags

The following options can influence how Marathon works:

#### Required Flags

* `--master` (Required): The URL of the Mesos master. The format is a
    comma-delimited list of of hosts like `zk://host1:port,host2:port/mesos`.
    If using ZooKeeper, pay particular attention to the leading `zk://` and
    trailing `/mesos`! If not using ZooKeeper, standard URLs like
    `http://localhost` are also acceptable.

#### Optional Flags

* `--artifact_store` (Optional. Default: None): URL to the artifact store.
    Examples: `"hdfs://localhost:54310/path/to/store"`,
    `"file:///var/log/store"`. For details, see the
    [artifact store](docs/artifact-store.md) docs.
* `--checkpoint` (Optional. Default: false): Enable checkpointing of tasks.
    Requires checkpointing enabled on slaves. Allows tasks to continue running
    during mesos-slave restarts and upgrades.
* `--executor` (Optional. Default: "//cmd"): Executor to use when none is
    specified.
* `--executor_health_checks` (Optional. Default: false)): If this flag is supplied,
    health checks are executed on the slaves on which the tasks are running.
    Requires Mesos `0.20.0` or higher. Use of this option limits app health
    checks to at most one. The only protocol supported by Mesos is COMMAND.
* `--failover_timeout` (Optional. Default: 604800 seconds (1 week)): The
    failover_timeout for Mesos in seconds.
* `--ha` (Optional. Default: true): Runs Marathon in HA mode with leader election.
    Allows starting an arbitrary number of other Marathons but all need to be
    started in HA mode. This mode requires a running ZooKeeper. See `--master`.
* `--hostname` (Optional. Default: hostname of machine): The advertised hostname
    stored in ZooKeeper so another standby host can redirect to the elected leader.
    _Note: Default is determined by
    [`InetAddress.getLocalHost`](http://docs.oracle.com/javase/7/docs/api/java/net/InetAddress.html#getLocalHost())._
* `--local_port_max` (Optional. Default: 20000): Max port number to use when
    assigning ports to apps.
* `--local_port_min` (Optional. Default: 10000): Min port number to use when
    assigning ports to apps.
* `--mesos_role` (Optional. Default: None): Mesos role for this framework.
* `--mesos_user` (Optional. Default: current user): Mesos user for
    this framework. _Note: Default is determined by
    [`SystemProperties.get("user.name")`](http://www.scala-lang.org/api/current/index.html#scala.sys.SystemProperties@get\(key:String\):Option[String])._
* `--reconciliation_initial_delay` (Optional. Default: 30000 (30 seconds)): The
    delay, in milliseconds, before Marathon begins to periodically perform task
    reconciliation operations.
* `--reconciliation_interval` (Optional. Default: 30000 (30 seconds)): The
    period, in milliseconds, between task reconciliation operations.
* `--task_launch_timeout` (Optional. Default: 60000 (60 seconds)): Time,
    in milliseconds, to wait for a task to enter the TASK_RUNNING state before
    killing it.
* `--zk` (Optional. Default: None): ZooKeeper URL for storing state.
    Format: `zk://host1:port1,host2:port2,.../path`
* `--zk_max_versions` (Optional. Default: None): Limit the number of versions
    stored for one entity.
* `--zk_timeout` (Optional. Default: 10000 (10 seconds)): Timeout for ZooKeeper
    in milliseconds.

#### Optional Flags Inherited from [Chaos](https://github.com/mesosphere/chaos)

* `--assets_path` (Optional. Default: None): Local file system path from which
    to load assets for the web UI. If not supplied, assets are loaded from the
    packaged JAR.
* `--http_credentials` (Optional. Default: None): Credentials for accessing the
    HTTP service in the format of `username:password`. The username may not
    contain a colon (:).
* `--http_port` (Optional. Default: 8080): The port on which to listen for HTTP
    requests.
* `--https_port` (Optional. Default: 8443): The port on which to listen for
    HTTPS requests.
* `--ssl_keystore_password` (Optional. Default: None): Password for the keystore
    supplied with the `ssl_keystore_path` option.
* `--ssl_keystore_path` (Optional. Default: None): Path to the SSL keystore. SSL
    will be enabled if this option is supplied.

### Configuration Options

* `MESOS_NATIVE_JAVA_LIBRARY`: `bin/start` searches the common installation paths,
    `/usr/lib` and `/usr/local/lib`, for the Mesos native library. If the
    library lives elsewhere in your configuration, set the environment variable
    `MESOS_NATIVE_JAVA_LIBRARY` to its full path.

  For example:

      MESOS_NATIVE_JAVA_LIBRARY=/Users/bob/libmesos.dylib ./bin/start --master local --zk zk://localhost:2181/marathon

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

### Developing Marathon with Vagrant image

This will enable you to run a local version of Marathon, for development purposes, without having to compile and configure a local Mesos environment.

1.	Clone the [playa-mesos repository](https://github.com/mesosphere/playa-mesos). Note that playa-mesos ships with a version of Mesos, Marathon, and ZooKeeper pre-configured.

2.	Sync local folders into Vagrant image

    Open `playa-mesos/Vagrantfile` and edit the `override.vm.synced_folder` setting:

    ```
    override.vm.synced_folder '</path/to/marathon/parent>', '/vagrant'
    ```
    Here `</path/to/marathon/parent>` is the absolute path to the folder containing Marathon.

3. SSH into your Vagrant image

    ```
    $ vagrant up # if not already running otherwise `vagrant reload`
    $ vagrant ssh
	```

4.	Check that your folders are synced correctly

	```
    $ cd /vagrant/
    $ ls
    marathon playa-mesos ...
    ```

5. Stop the Marathon that is pre-configured in the Vagrant image

    ```
    $ sudo stop marathon
    ```
6. Add `marathon-start` alias to start your own version of Marathon

    ```
    $ nano ~/.bash_aliases
    ```
    add the following in the top of that file and save it:

    ```
    # setup marathon easy run
    alias 'start-marathon'='./bin/start --master zk://localhost:2181/mesos --zk_hosts localhost:2181 --assets_path src/main/resources/assets'
    ```
7.	Refresh the terminal and run the `start-marathon` command in the marathon folder

    ```
    $ . ~/.bashrc
    $ cd /vagrant/marathon/
    $ start-marathon
    ```

When you're done use `vagrant halt` to shut down the Vagrant instance and spare your battery life.

## Companies using Marathon

* [Airbnb](https://www.airbnb.com/)
* [eBay](http://www.ebay.com/)
* [The Factory](https://github.com/thefactory/)
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

[![githalytics.com alpha](https://cruel-carlota.gopagoda.com/678b61f70ab36917caf159d22ba55f76 "githalytics.com")](http://githalytics.com/mesosphere/marathon)

[Chronos]: https://github.com/airbnb/chronos "Airbnb's Chronos"
[Mesos]: https://mesos.apache.org/ "Apache Mesos"
[Zookeeper]: https://zookeeper.apache.org/ "Apache Zookeeper"
[Storm]: http://storm-project.net/ "distributed realtime computation"
[freenode]: https://freenode.net/ "IRC channels"
[upstart]: http://upstart.ubuntu.com/ "Ubuntu's event-based daemons"
[init]: https://en.wikipedia.org/wiki/Init "init"
[Mesosphere]: http://mesosphere.io/ "Mesosphere"
