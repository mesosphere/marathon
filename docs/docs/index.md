---
title: Setting Up and Running Marathon
---

## Setting Up And Running Marathon

### Quickstart on Google Cloud Platform

A quick way to try out Marathon is to launch a cluster using
[Mesosphere for Google Cloud Platform](https://google.mesosphere.io).
Each cluster has Mesos and Marathon configured and offers secure access
over a custom VPN.

### Requirements

* [Apache Mesos][Mesos] 0.15.0+
* [Apache ZooKeeper][ZooKeeper]
* JDK 1.6+
* Scala 2.10+
* sbt 0.13.5

### Installation

#### Install Mesos

One easy way is via your system's package manager.
Current builds for major Linux distributions and Mac OS X are available
from on the Mesosphere [downloads page](http://mesosphere.com/downloads/)
or from Mesosphere's [repositories](http://mesosphere.com/2014/07/17/mesosphere-package-repositories/).

If building from source, see the
Mesos [Getting Started](http://mesos.apache.org/gettingstarted/) page or the
[Mesosphere tutorial](http://mesosphere.com/2013/08/01/distributed-fault-tolerant-framework-apache-mesos/)
for details. Running `make install` will install Mesos in `/usr/local` in
the same way as these packages do.

#### Install Marathon

#### Through your Package Manager

Marathon packages are available from Mesosphere's [repositories](http://mesosphere.com/2014/07/17/mesosphere-package-repositories/).

#### From a Tarball

1.  Download and unpack the latest Marathon release.

    **For Mesos 0.20.0:**

    ``` bash
    $ curl -O http://downloads.mesosphere.com/marathon/v0.7.1/marathon-0.7.1.tgz
    $ tar xzf marathon-0.7.1.tgz
    ```

    **For Mesos 0.19.0:**

    ``` bash
    $ curl -O http://downloads.mesosphere.com/marathon/marathon-0.6.1/marathon-0.6.1.tgz
    $ tar xzf marathon-0.6.1.tgz
    ```

    **For Mesos 0.17.0 to 0.18.2:**

    ``` console
    $ curl -O http://downloads.mesosphere.com/marathon/marathon-0.5.1/marathon-0.5.1.tgz
    $ tar xzf marathon-0.5.1.tgz
    ```

    **For Mesos 0.16.0 and earlier:**

    ``` console
    $ curl -O http://downloads.mesosphere.com/marathon/marathon-0.5.1_mesos-0.16.0/marathon-0.5.1_mesos-0.16.0.tgz
    $ tar xzf marathon-0.5.1_mesos-0.16.0.tgz
    ```

    SHA-256 checksums are available by appending `.sha256` to the URLs.


### Upgrading to a Newer Version

Upgrading to a newer version of Marathon should be seamless. Be aware that
downgrading from versions >= 0.7.0 to older versions is not possible
because of incompatible changes in the data format.

We recommend creating a backup of the ZooKeeper state before upgrading to be able to downgrade in case
of problems after an upgrade. This can done by creating a copy of ZooKeeper's [data directory](http://zookeeper.apache.org/doc/r3.1.2/zookeeperAdmin.html#The+Data+Directory).

[See here](https://mesosphere.github.io/marathon/docs/upgrade/06xto070.html) for an upgrade guide from 0.6.* to 0.7.0

### Running in Production Mode

To launch Marathon in *production mode*, you need to have both
ZooKeeper and Mesos running. The following command launches
Marathon in *production mode*. Point your web browser to
`localhost:8080` and you should see the Marathon UI.

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

```console
$ MESOS_NATIVE_JAVA_LIBRARY=/Users/bob/libmesos.dylib ./bin/start --master local --zk zk://localhost:2181/marathon
```



[Mesos]: https://mesos.apache.org/ "Apache Mesos"
[Zookeeper]: https://zookeeper.apache.org/ "Apache ZooKeeper"
