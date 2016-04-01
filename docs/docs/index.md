---
title: Setting Up and Running Marathon
---


## Installing Marathon


### Requirements

* [Apache Mesos][Mesos] 0.28.0+
* [Apache ZooKeeper][ZooKeeper]
* JDK 1.8+

### Installation

#### Install Mesos

Marathon runs atop Apache Mesos. You can install Mesos via your system's package manager.
Current builds and instructions on how to set up repositories for major Linux distributions are available on the Mesosphere [downloads page](http://mesosphere.com/downloads/).

If you want to build Mesos from source, see the
Mesos [Getting Started](http://mesos.apache.org/gettingstarted/) page or the
[Mesosphere tutorial](http://mesosphere.com/2013/08/01/distributed-fault-tolerant-framework-apache-mesos/)
for details. Running `make install` will install Mesos in `/usr/local`.

#### Install Marathon

#### Through your Package Manager

Marathon packages are available from Mesosphere's [repositories](http://mesosphere.com/2014/07/17/mesosphere-package-repositories/).

#### From a Tarball

Download and unpack the latest Marathon release.

``` bash
$ curl -O http://downloads.mesosphere.com/marathon/v1.0.0-RC1/marathon-1.0.0-RC1.tgz
$ tar xzf marathon-1.0.0-RC1.tgz
```

SHA-256 checksums are available by appending `.sha256` to the URLs.

### Versioning

As of version 0.9.0, Marathon adheres to [semantic versioning](http://semver.org).
That means our documented REST API remains compatible across releases unless we change the MAJOR version
(the first number in the version tuple). If you depend on undocumented features, please tell us about them by [raising a GitHub issue](https://github.com/mesosphere/marathon/issues/new). Portions of the API marked as EXPERIMENTAL are exempt from this rule. We do not introduce new features in PATCH version increments (the last number in the version tuple).

In rare cases, we may change the Marathon command line flags in a MINOR version upgrade. The release notes document these instances.

We provide release candidates for all new MAJOR/MINOR versions and invite our users to test them and
give us feedback, particularly on violations of the versioning policy.

### Upgrading to a Newer Version

See [the Marathon upgrade guide](https://mesosphere.github.io/marathon/docs/upgrade/index.html) to learn how to upgrade to a new version.

### Running in High Availability Mode

Both ZooKeeper and Mesos need to be running in order to launch Marathon in *high availability mode*.

Point your web browser to
`localhost:8080` and you should see the [Marathon UI]({{ site.baseurl }}/docs/marathon-ui.html).

``` console
$ ./bin/start --master zk://zk1.foo.bar:2181,zk2.foo.bar:2181/mesos --zk zk://zk1.foo.bar:2181,zk2.foo.bar:2181/marathon
```

Marathon uses `--master` to find the Mesos masters, and `--zk` to find ZooKeepers
for storing state. They are separate options because Mesos masters can also be
discovered in other ways.

For all configuration options, see the [command line flags](command-line-flags.html) doc. For more information on the high-availability feature of Marathon, see the [high availability](high-availability.html) doc.

### Mesos Library

`MESOS_NATIVE_JAVA_LIBRARY`: `bin/start` searches the common installation paths,
`/usr/lib` and `/usr/local/lib`, for the Mesos native library. If the
library lives elsewhere in your configuration, set the environment variable
`MESOS_NATIVE_JAVA_LIBRARY` to its full path.

For example:

```console
$ MESOS_NATIVE_JAVA_LIBRARY=/Users/bob/libmesos.dylib ./bin/start --master local --zk zk://localhost:2181/marathon
```

### Launch an Application

For an introduction to Marathon application definitions and how they are executed, see [Application Basics](application-basics.html).

[Mesos]: https://mesos.apache.org/ "Apache Mesos"
[Zookeeper]: https://zookeeper.apache.org/ "Apache ZooKeeper"
