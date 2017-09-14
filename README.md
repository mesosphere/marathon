# [Marathon](https://mesosphere.github.io/marathon/) [![Build Status](https://jenkins.mesosphere.com/service/jenkins/buildStatus/icon?job=marathon-pipelines/master)](https://jenkins.mesosphere.com/service/jenkins/buildStatus/icon?job=marathon-pipelines/master) [![Issues](https://img.shields.io/badge/Issues-JIRA-ff69b4.svg?style=flat)](https://jira.mesosphere.com/projects/MARATHON/issues/)

Marathon is a production-proven [Apache Mesos][Mesos] framework for container orchestration. [DC/OS](https://dcos.io/get-started/#marathon) is the easiest way to start using Marathon. Issues are tracked in [JIRA](https://jira.mesosphere.com/projects/MARATHON/issues/).

Marathon provides a
[REST API](https://mesosphere.github.io/marathon/docs/rest-api.html) for
starting, stopping, and scaling applications. Marathon is written in Scala and
can run in highly-available mode by running multiple copies. The
state of running tasks gets stored in the Mesos state abstraction.

Marathon is a *meta framework*: you can start other Mesos frameworks such as
Chronos or [Storm][Storm] with it to ensure they survive machine failures.
It can launch anything that can be launched in a standard shell. In fact, you
can even start other Marathon instances via Marathon.

Since Marathon version 0.7.0 and Mesos 0.20.0, you can [deploy, run and scale Docker containers](https://mesosphere.github.io/marathon/docs/native-docker.html) easily with native support.

## Features

* *HA* -- run any number of Marathon schedulers, but only one gets elected as
    leader; if you access a non-leader, your request gets proxied to the
    current leader
* *[Constraints](https://mesosphere.github.io/marathon/docs/constraints.html)* - e.g., only one instance of an application per rack, node, etc.
* *[Service Discovery &amp; Load Balancing](https://mesosphere.github.io/marathon/docs/service-discovery-load-balancing.html)* via HAProxy or the events API (see below).
* *[Health Checks](https://mesosphere.github.io/marathon/docs/health-checks.html)*: check your application's health via HTTP or TCP checks.
* *[Event Subscription](https://mesosphere.github.io/marathon/docs/rest-api.html#event-subscriptions)* lets you supply an HTTP endpoint to receive notifications, for example to integrate with an external load balancer.
* *[Marathon UI](https://mesosphere.github.io/marathon/docs/marathon-ui.html)*
* *[JSON/REST API](https://mesosphere.github.io/marathon/docs/rest-api.html)* for easy integration and scriptability
* *Basic Auth* and *SSL*
* *[Metrics](https://mesosphere.github.io/marathon/docs/metrics.html)*:
  query them at `/metrics` in JSON format or push them to graphite/statsd/datadog.

## Documentation

Marathon documentation is available on the [Marathon GitHub pages site](http://mesosphere.github.io/marathon/).

Documentation for installing and configuring the full Mesosphere stack including Mesos and Marathon is available on the [Mesosphere website](http://docs.mesosphere.com).

## Issue Tracking

Marathon uses [JIRA](https://jira.mesosphere.com/projects/MARATHON) to track issues. You can [browse](https://jira.mesosphere.com/projects/MARATHON/issues/) existing issues or [file a new issue](https://jira.mesosphere.com/secure/CreateIssue!default.jspa?pid=10401) with your GitHub account.

Note for users of GitHub issues: All existing issues have been migrated and closed, and a reference to the related [JIRA](https://jira.mesosphere.com/projects/MARATHON) has been added as a comment.
We leave the GitHub issues available for reference. Going forward please use [JIRA](https://jira.mesosphere.com/projects/MARATHON) always.

### Contributing

We heartily welcome external contributions to Marathon's documentation. Documentation should be committed to the `master` branch and published to our GitHub pages site using the instructions in [docs/README.md](https://github.com/mesosphere/marathon/tree/master/docs).

## Setting Up And Running Marathon

### Dependencies
Marathon has the following compile-time dependencies:
* sbt - A build tool for scala. You can find the instructions for installing sbt for Mac OS X and Linux over [here](http://www.scala-sbt.org/0.13/tutorial/Setup.html).
* JDK 1.8+

For run-time, Marathon has the following dependencies:
* libmesos - JNI bindings for talking to Apache Mesos master. Look at the *Install Mesos* section for instructions to get libmesos.
* Apache Zookeeper - You can have a separate Zookeeper installation specifically for Marathon, or you can use the same Zookeeper used by Mesos.

### Installation

#### Getting started with [DC/OS](https://dcos.io/get-started/#marathon)
The by far easiest way to get Marathon running is to use [DC/OS](https://dcos.io/get-started/#marathon). Marathon is pre-bundled into [DC/OS](https://dcos.io/get-started/#marathon).

#### Install Mesos
Marathon requires libmesos, a shared object library, that contains JNI bindings for Marathon to talk to the Mesos master. *libmesos* comes as part of the Apache Mesos installation. There are three options for installing Apache Mesos.

##### Installing Mesos from prepackaged releases
Instructions on how to install prepackaged releases of Mesos are available [in the Marathon docs](https://mesosphere.github.io/marathon/docs/).

##### Building Mesos from source
**NOTE:** *Choose this option only if building Marathon from source, else there might be version incompatibility between pre-packaged releases of Marathon and Mesos built from source.*

You can find the instructions for compiling Mesos from source in the [Apache Mesos getting started docs](http://mesos.apache.org/gettingstarted/). If you want Mesos to install libraries and executables in a non-default location use the --prefix option during configuration as follows:

```console
./configure --prefix=<path to Mesos installation>
```

The `make install` will install libmesos (libmesos.so on Linux and libmesos.dylib on Mac OS X) in the install directory.

##### Using the Mesos Version Manager
**NOTE:** *Choose this option only if building Marathon from source, else there might be version incompatibility between pre-packaged releases of Marathon and Mesos built from source.*  

The Mesos Version Manager (mvm) compiles, configures, and manages multiple versions of Apache Mesos.
It allows switching between versions quickly, making it easy to test Marathon against different versions of Mesos.

###### Prerequisites

The Mesos Version Manager assumes that all dependencies of Apache Mesos are readily installed.  
Please refer to the [Apache Mesos getting started docs](http://mesos.apache.org/gettingstarted/) for instructions on how to set up the build environment.

MVM compiles Mesos with SSL support by default, which requires openssl and libevent to be installed.  
On macOS, the packages can be installed using brew: `brew install openssl libevent`  
On CentOS, the packages can be installed using yum: `sudo yum install -y libevent-devel openssl-devel`

###### Usage

The script can be run as follows:

        cd marathon
        cd scripts
        ./mvm.sh <VERSION> [SHELL]

The following command will launch a bash shell configured for Mesos 1.2.0: `./mvm.sh 1.2.0 bash`

You should consider placing the script into a folder in your shell's `PATH` if you are using it regularly.

The mvm script accepts three different formats as version name:

1. Version tags from the Mesos repository. Use `./mvm.sh --tags` in order to obtain a list of available tags.
2. Commit hashes from the Mesos repository.
3. The `--latest` flag, which automatically chooses the latest development version: `./mvm.sh --latest`.

MVM Will automatically download & compile Apache Mesos if necessary.
It will then spawn a new bash shell with the chosen version of Mesos activated.  
For more information see `./mvm.sh --help`.

**Note**: You will have to re-run the script if you wish to use Mesos after closing the shell.
See `./mvm.sh --help` information on how to  permanently configure your shell for mvm to avoid this.

#### Install Marathon

Instructions on how to install prepackaged releases are available [in the Marathon docs](https://mesosphere.github.io/marathon/docs/). Alternatively, you can build Marathon from source.

##### Building from Source

1.  To build Marathon from source, check out this repo and use sbt to build a universal:

        git clone https://github.com/mesosphere/marathon.git
        cd marathon
        sbt 'run --master localhost:5050 --zk zk://localhost:2181/marathon'
    
    **Troubleshooting**
    1. Failure in retrieval of IP address of the local machine will result in an error and may look like this:
    
        `Failed to obtain the IP address for '<local-machine>'; the DNS service may not be able to resolve it: nodename nor servname provided, or not known`
        
        Make sure that `LIBPROCESS_IP` environment variable is set.
        ```
        export LIBPROCESS_IP="127.0.0.1"
        ```
    1. When the `MESOS_NATIVE_JAVA_LIBRARY` environment variable is not set, the following error may occur,
        
        `java.lang.UnsatisfiedLinkError: no mesos in java.library.path...`
        
        Make sure that `MESOS_NATIVE_JAVA_LIBRARY` environment variable is set.
        ```
        export MESOS_NATIVE_JAVA_LIBRARY="/path/to/mesos/lib/libmesos.dylib"
        ```
   
1.  Run `sbt universal:packageZipTarball` to package Marathon as an txz file
    containing bin/marathon fully packaged.

1. Run `sbt docker:publishLocal` for a local marathon docker image.

### Running in Development Mode

Mesos local mode allows you to run Marathon without launching a full Mesos
cluster. It is meant for experimentation and not recommended for production
use. Note that you still need to run ZooKeeper for storing state. The following
command launches Marathon on Mesos in *local mode*. Point your web browser to
`http://localhost:8080` to see the Marathon UI.

        mesos-local
        sbt 'run --master localhost:5050 --zk zk://localhost:2181/marathon'

For more information on how to run Marathon in production and configuration
options, see [the Marathon docs](https://mesosphere.github.io/marathon/docs/).

## Developing Marathon

See [the documentation](https://mesosphere.github.io/marathon/docs/developing-vm.html) on how to run Marathon locally inside a virtual machine.

### Running in Development Mode on Docker

* Note: Currently the Docker container fails due to strange behavior from the latest Mesos version.  There will be an error about `work_dir` that is still unresolved, much like this:

        Failed to start a local cluster while loading agent flags from the environment: Flag 'work_dir' is required, but it was not provided

Build it:

    sbt docker:publishLocal

Note the version, e.g: `[info] Built image mesosphere/marathon:1.5.0-SNAPSHOT-461-gf1cc63e` => `1.5.0-SNAPSHOT-461-gf1cc63e`


A running zookeeper instance is required, if there isn't one already available, there is a docker image available for this:

    docker run --name some-zookeeper --restart always -d zookeeper

Run it with zookeeper container:

    docker run --link some-zookeeper:zookeeper marathon-head --master local --zk zk://zookeeper:2181/marathon

Or run it without zookeeper container:

    docker run marathon:{version} --master local --zk zk://localhost:2181/marathon

If you want to inspect the contents of the Docker container:

    docker run -it --entrypoint=/bin/bash marathon:{version} -s

### Testing

The tests and integration tests a run with:

    sbt test integration:test

You have to set the Mesos test IP and disable Docker tests on Mac:

    MESOSTEST_IP_ADDRESS="127.0.0.1" \
    RUN_DOCKER_INTEGRATION_TESTS=false \
    RUN_MESOS_INTEGRATION_TESTS=false \
    sbt test integration:test

The Docker integration tests are not supported on Mac. The tests start and stop
local Mesos clusters and Marathon instances. Sometimes processes leak after
failed test runs. You can check them with `ps aux | grep "python|java|mesos"`
and kill all `app_mock.py` processes and Mesos and Marathon instances unless
they do not belong to a production environment of course.

Also see the [CI instructions](ci/README.md) on running specfic build pipeline
targets.

### Marathon UI

To develop on the web UI look into the instructions of the [Marathon UI](https://github.com/mesosphere/marathon-ui) repository.

## Marathon Clients

* [marathonctl](https://github.com/shoenig/marathonctl) A handy CLI tool for controlling Marathon
* [Ruby gem and command line client](https://rubygems.org/gems/marathon-api)

    Running Chronos with the Ruby Marathon Client:

        marathon -M http://foo.bar:8080 start -i chronos -u https://s3.amazonaws.com/mesosphere-binaries-public/chronos/chronos.tgz \
            -C "./chronos/bin/demo ./chronos/config/nomail.yml \
            ./chronos/target/chronos-1.0-SNAPSHOT.jar" -c 1.0 -m 1024
* [Ruby gem marathon_deploy](https://github.com/eBayClassifiedsGroup/marathon_deploy) alternative command line tool to deploy using json or yaml files with ENV macros.
* [Scala client](https://github.com/guidewire/marathon-client), developed at Guidewire
* [Java client](https://github.com/mohitsoni/marathon-client) by Mohit Soni
* [Maven plugin](https://github.com/dcos-labs/dcos-maven-plugin), developed by [Johannes Unterstein](https://github.com/unterstein)
* [Maven plugin](https://github.com/holidaycheck/marathon-maven-plugin), developed at [HolidayCheck](http://www.holidaycheck.com/)
* [Python client](https://github.com/thefactory/marathon-python), developed at [The Factory](http://www.thefactory.com)
* [Python client](https://github.com/Wizcorp/marathon-client.py), developed at [Wizcorp](http://www.wizcorp.jp)
* [Go client](https://github.com/gambol99/go-marathon) by Rohith Jayawardene
* [Go client](https://github.com/jbdalido/gomarathon) by Jean-Baptiste Dalido
* [Node client](https://github.com/silas/node-mesos) by Silas Sewell
* [Clojure client](https://github.com/codemomentum/marathonclj) by Halit Olali
* [sbt plugin](https://github.com/Tapad/sbt-marathon), developed at [Tapad](https://tapad.com)

## Companies using Marathon

Across all installations Marathon is managing applications on more than 100,000 nodes world-wide. These are some of the companies using it:

* [Adform](http://site.adform.com/)
* [Alauda](http://www.alauda.cn/)
* [Allegro](http://allegro.tech)
* [AllUnite](https://allunite.com/)
* [Argus Cyber Security](http://argus-sec.com/)
* [Artirix](http://www.artirix.com/)
* [Arukas](https://arukas.io/)
* [bol.com](https://www.bol.com/)
* [Brand24](https://brand24.com/)
* [Branding Brand](http://www.brandingbrand.com/)
* [China Mobile](http://www.chinamobileltd.com/en/global/home.php)
* [China Unicom](http://eng.chinaunicom.com/index/index.html)
* [Corvisa](https://www.corvisa.com/)
* [Criteo](http://www.criteo.com/)
* [Daemon](http://www.daemon.com.au/)
* [DataMan](http://www.shurenyun.com/)
* [DHL Parcel](https://www.dhlparcel.nl/)
* [Disqus](https://disqus.com/)
* [DueDil](https://www.duedil.com/)
* [eBay](http://www.ebay.com/)
* [The Factory](https://github.com/thefactory/)
* [Football Radar](http://www.footballradar.com)
* [Guidewire](https://www.guidewire.com/)
* [Groupon](https://www.groupon.com/)
* [GSShop](http://www.gsshop.com/)
* [GrowingIO](https://www.growingio.com/)
* [HolidayCheck](http://www.holidaycheck.com/)
* [Human API](https://humanapi.co/)
* [Indix](http://www.indix.com/)
* [ING](http://www.ing.com/)
* [iQIYI](http://www.iqiyi.com/)
* [LaunchKey](https://launchkey.com/)
* [Mapillary](https://www.mapillary.com/)
* [Measurence](http://www.measurence.com/)
* [Motus](http://www.motus.com/)
* [Notonthehighstreet](http://www.notonthehighstreet.com/)
* [OpenTable](http://www.opentable.com/)
* [Opera](https://www.opera.com)
* [Orbitz](http://www.orbitz.com/)
* [Otto](https://www.otto.de/)
* [OVH](https://ovh.com/)
* [PayPal](https://www.paypal.com)
* [Qubit](http://www.qubit.com/)
* [RelateIQ](https://www.salesforceiq.com/)
* [Refinery29](https://www.refinery29.com)
* [Sailthru](http://www.sailthru.com/)
* [SAKURA Internet Inc](https://www.sakura.ad.jp/)
* [sloppy.io](http://sloppy.io/)
* [SmartProcure](https://smartprocure.us/)
* [Strava](https://www.strava.com)
* [Sveriges Television](http://www.svt.se)
* [T2 Systems](http://t2systems.com)
* [Tapad](https://tapad.com)
* [Teradata](http://www.teradata.com)
* [trivago](http://www.trivago.com/)
* [VANAD Enovation](http://www.vanadenovation.nl/)
* [Viadeo](http://www.viadeo.com)
* [Wikia](http://www.wikia.com/Wikia)
* [William Hill](https://www.williamhill.com)
* [WooRank](https://www.woorank.com/)
* [Yelp](http://www.yelp.com/)

Not in the list? Open a pull request and add yourself!

## Help

Have you found an issue? Feel free to report it using our [JIRA Issues](https://jira.mesosphere.com/projects/MARATHON/summary) page.
In order to speed up response times, we ask you to provide as much
information on how to reproduce the problem as possible. If the issue is related
 in any way to the web ui, we kindly ask you to use the `gui` label.

If you have questions, please post on the
[Marathon Framework Group](https://groups.google.com/forum/?hl=en#!forum/marathon-framework)
email list. You can find Marathon support in the `#marathon` channel, and Mesos
support in the `#mesos` channel, on [freenode][freenode] (IRC). The team at
[Mesosphere][Mesosphere] is also happy to answer any questions.

If you'd like to take part in design research and test new features in Marathon before they're released, please add your name to our [UX Research](http://uxresearch.mesosphere.com) list.

## Authors

Marathon was created by [Tobias Knaup](https://github.com/guenter) and
[Florian Leibert](https://github.com/florianleibert) and continues to be
developed by the team at Mesosphere and by many contributors from
the community.

[Chronos]: https://github.com/mesos/chronos "Airbnb's Chronos"
[Mesos]: https://mesos.apache.org/ "Apache Mesos"
[Zookeeper]: https://zookeeper.apache.org/ "Apache Zookeeper"
[Storm]: http://storm.apache.org "distributed realtime computation"
[freenode]: https://freenode.net/ "IRC channels"
[upstart]: http://upstart.ubuntu.com/ "Ubuntu's event-based daemons"
[init]: https://en.wikipedia.org/wiki/Init "init"
[Mesosphere]: https://mesosphere.com/ "Mesosphere"

## Acknowledgements

**YourKit, LLC**

![YourKit, LLC](https://www.yourkit.com/images/yklogo.png)

YourKit supports open source projects with its full-featured Java
Profiler.
YourKit, LLC is the creator of <a
href="https://www.yourkit.com/java/profiler/index.jsp">YourKit Java
Profiler</a>
and <a href="https://www.yourkit.com/.net/profiler/index.jsp">YourKit
.NET Profiler</a>,
innovative and intelligent tools for profiling Java and .NET
applications.
