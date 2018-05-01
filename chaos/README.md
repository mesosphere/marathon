# Chaos

A lightweight framework for writing REST services in Scala.

[Chaos](https://en.wikipedia.org/wiki/Chaos_%28cosmogony%29) (Greek χάος, khaos) refers to the formless or void state preceding the creation of the universe or cosmos in the Greek creation myths. Chaos (the framework) precedes creation of a universe of services.

## Why yet another framework?

At [Mesosphere](https://mesosphere.com/) we're building REST services in Scala, and we wanted a solid foundation. We had experience with [Dropwizard](https://github.com/dropwizard/dropwizard) and [Twitter Commons](https://github.com/twitter/commons), which are both great Java frameworks, but are a bit hard to use from Scala.
We also experimented with [Play!](https://github.com/playframework/playframework), but it does many things besides REST, which adds unnecessary baggage.

### Design Goals

We wanted a framework that

* is easy to use
* does one thing really well (REST)
* feels good in Scala
* is built on battle-tested and well-supported libraries
* doesn't try to reinvent the wheel

### Building Blocks

There are great JVM libraries for every part of a REST stack. Chaos just glues these together.

* [Jersey](https://jersey.java.net/) for REST via annotations
* [Guice](https://github.com/google/guice) for dependency injection
* [Guava](https://code.google.com/p/guava-libraries/) for lifecycle management and various utilities
* [Jetty](http://www.eclipse.org/jetty/) as the web server and servlet container
* [Jackson](http://wiki.fasterxml.com/JacksonHome) for JSON support
* [Hibernate Validator](http://hibernate.org/subprojects/validator.html) for validating API requests
* [Coda Hale's Metrics](https://github.com/codahale/metrics) for JVM and application metrics

## Getting Started

### Requirements

* JDK 1.8+
* SBT 0.13.x+

### Example App

There is an example app in [src/main/scala/mesosphere/chaos/example]
(https://github.com/mesosphere/chaos/blob/master/src/main/scala/mesosphere/chaos/example/Main.scala). To run the example:

    sbt run

Make requests to the example endpoints with [HTTPie](https://github.com/jkbrzt/httpie):

    http localhost:8080/foo
    http localhost:8080/foo name=Bunny age=42

### Built in Endpoints

* `/ping` - health check.
* `/metrics` - metrics as JSON
* `/logging` - configure log levels at runtime

### Using Chaos in your Project

Chaos releases are available from Mesosphere's Maven repository.

#### Maven

To add Chaos to a Maven project, add this to your `pom.xml`:

    <properties>
        <chaos.version>0.5.2</chaos.version>
    </properties>

    ...

    <repositories>
        <repository>
            <id>mesosphere-public-repo</id>
            <name>Mesosphere Public Repo</name>
            <url>http://downloads.mesosphere.io/maven</url>
        </repository>
    </repositories>

    ...

    <dependencies>
        <dependency>
            <groupId>mesosphere</groupId>
            <artifactId>chaos</artifactId>
            <version>${chaos.version}</version>
        </dependency>
    </dependencies>

#### SBT

To add Chaos to an SBT project, add this to your `build.sbt`:

    resolvers += "Mesosphere Public Repo" at "http://downloads.mesosphere.io/maven"

    libraryDependencies ++= Seq(
      "mesosphere" % "chaos" % "0.5.2",
      "com.sun.jersey" % "jersey-bundle" % "1.17.1"
    )


## Getting Help

If you have questions, please post on the [Chaos Users Group](https://groups.google.com/forum/?hl=en#!forum/chaos-users) email list.
The team at [Mesosphere](https://mesosphere.com/) is also happy to answer any questions.

## Authors

* [Tobias Knaup](https://github.com/guenter)
* [Florian Leibert](https://github.com/florianleibert)

## Current Users

* [Marathon](https://github.com/mesosphere/marathon), an Apache Mesos framework for long-running services.
* [Chronos](https://github.com/mesos/chronos), a fault tolerant job scheduler that handles dependencies and ISO8601 based schedules.
