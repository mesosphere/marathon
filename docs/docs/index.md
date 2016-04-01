-----------------------
title: Install Marathon
-----------------------

## Install Marathon


### Requirements

* [Apache Mesos][Mesos] 0.24.0+
* [Apache ZooKeeper][ZooKeeper]
* JDK 1.8+ 

### Installation

#### Install Mesos

One easy way is via your system's package manager.
Current builds and instructions on how to set up repositories for major Linux distributions are available on the Mesosphere [downloads page](http://mesosphere.com/downloads/).

If building from source, see the
Mesos [Getting Started](http://mesos.apache.org/gettingstarted/) page or the
[Mesosphere tutorial](http://mesosphere.com/2013/08/01/distributed-fault-tolerant-framework-apache-mesos/)
for details. Running `make install` will install Mesos in `/usr/local` in
the same way as these packages do.

#### Install Marathon

#### Through your Package Manager

Marathon packages are available from Mesosphere's [repositories](http://mesosphere.com/2014/07/17/mesosphere-package-repositories/).

#### From a Tarball

Download and unpack the latest Marathon release.

``` bash
$ curl -O http://downloads.mesosphere.com/marathon/v0.15.2/marathon-0.15.2.tgz
$ tar xzf marathon-0.15.2.tgz
```

SHA-256 checksums are available by appending `.sha256` to the URLs.

### Upgrading to a Newer Version

[See here]({{ site.baseurl }}/docs/upgrade/index.html) for our guide on upgrading to a new version.

### Running in High Availability (HA) Mode

To launch Marathon in *high availability mode*, you need to have both
ZooKeeper and Mesos running. The following command launches
Marathon in *high availability mode*. Point your web browser to
`localhost:8080` and you should see the [Marathon UI]({{ site.baseurl }}/docs/marathon-ui.html).

``` console
$ ./bin/start --master zk://zk1.foo.bar:2181,zk2.foo.bar:2181/mesos --zk zk://zk1.foo.bar:2181,zk2.foo.bar:2181/marathon
```

Marathon uses `--master` to find the Mesos masters, and `--zk` to find ZooKeepers
for storing state. They are separate options because Mesos masters can be
discovered in other ways as well.

For all configuration options, see the [command line flags](command-line-flags.html) doc. For more information on the high-availability feature of Marathon, see the [high availability](high-availability.html) doc.

### Mesos Library

`MESOS_NATIVE_JAVA_LIBRARY`: `bin/start` searches the common installation paths,
`/usr/lib` and `/usr/local/lib`, for the Mesos native library. If the
library lives elsewhere in your configuration, set the environment variable
`MESOS_NATIVE_JAVA_LIBRARY` to its full path.

For example:

OS X
```console
$ MESOS_NATIVE_JAVA_LIBRARY=/Users/bob/libmesos.dylib ./bin/start --master local --zk zk://localhost:2181/marathon
```

Linux
```console
$ MESOS_NATIVE_JAVA_LIBRARY=/Users/bob/libmesos.so ./bin/start --master local --zk zk://localhost:2181/marathon
```

### Launch an Application

For an introduction to Marathon application definitions and how they are executed, see [Application Basics](application-basics.html).

[Mesos]: https://mesos.apache.org/ "Apache Mesos"
[Zookeeper]: https://zookeeper.apache.org/ "Apache ZooKeeper"
