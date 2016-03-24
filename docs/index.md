---
title: A container orchestration platform for Mesos and DCOS
---

<div class="jumbotron text-center">
  <h1>Marathon</h1>
  <p class="lead">
    A container orchestration platform for Mesos and DCOS
  </p>
  <p>
    <a href="http://downloads.mesosphere.com/marathon/v1.0.0-RC1/marathon-1.0.0-RC1.tgz"
        class="btn btn-lg btn-primary">
      Download Marathon v1.0.0-RC1
    </a>
  </p>
  <a class="btn btn-link"
      href="http://downloads.mesosphere.com/marathon/v1.0.0-RC1/marathon-1.0.0-RC1.tgz.sha256">
    v1.0.0-RC1 SHA-256 Checksum
  </a> &middot;
  <a class="btn btn-link"
      href="https://github.com/mesosphere/marathon/releases/tag/v1.0.0-RC1">
    v1.0.0-RC1 Release Notes
  </a>
</div>

## Overview

Marathon is a production-grade container orchestration platform for [Apache Mesos](https://mesos.apache.org/) and [Mesosphere DCOS](https://mesosphere.com/product/).

## Features

- [High Availability](https://mesosphere.github.io/marathon/docs/high-availability.html). Marathon runs as an active/passive cluster with leader election for 100% uptime.
- Multiple container runtimes. Marathon has first-class support for both [Mesos containers](https://mesosphere.github.io/marathon/docs/application-basics.html) (using cgroups) and [Docker](https://mesosphere.github.io/marathon/docs/native-docker.html).
- [Stateful apps](https://mesosphere.github.io/marathon/docs/persistent-volumes.html). Marathon can bind persistent storage volumes to your application. You can run databases like MySQL and Postgres, and have storage accounted for by Mesos.
- [Beautiful and powerful UI](https://mesosphere.github.io/marathon/docs/marathon-ui.html).
- [Constraints](https://mesosphere.github.io/marathon/docs/constraints.html). e.g. Only one instance of an application per rack, node, etc.
- [Service Discovery & Load Balancing](https://mesosphere.github.io/marathon/docs/service-discovery-load-balancing.html). Several methods available.
- [Health Checks](https://mesosphere.github.io/marathon/docs/health-checks.html). Evaluate your application's health using HTTP or TCP checks.
- [Event Subscription](https://mesosphere.github.io/marathon/docs/rest-api.html#event-subscriptions). Supply an HTTP endpoint to receive notifications - for example to integrate with an external load balancer.
- [Metrics](https://mesosphere.github.io/marathon/docs/metrics.html). Query them at /metrics in JSON format or push them to systems like graphite, statsd and Datadog.
- [Complete REST API](https://mesosphere.github.io/marathon/docs/rest-api.html) for easy integration and scriptability.

## DCOS features

Running on DCOS, Marathon gains the following additional features:

- Virtual IP routing. Allocate a dedicated, virtual address to your app. Your app is now reachable anywhere in the cluster, wherever it might be scheduled. Load balancing and rerouting around failures are done automatically.
- Authorization (DCOS Enterprise Edition only). True multitenancy with each user or group having access to their own applications and groups.

## Marathon example: A small datacenter

The graphic shown below depicts how Marathon runs on top of
<a href="https://mesos.apache.org/">Apache Mesos</a> together with
the <a href="https://github.com/airbnb/chronos">Chronos</a> framework.
In this case, Marathon is the first framework to be
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

<p class="text-center">
  <img src="{{ site.baseurl}}/img/architecture.png" width="423" height="477" alt="">
</p>

The next graphic shows a more application-centric view of Marathon running
three applications, each with a different number of tasks: Search (1), Jetty
(3), and Rails (5).

<p class="text-center">
  <img src="{{ site.baseurl}}/img/marathon1.png" width="420" height="269" alt="">
</p>

As the website gains traction and the user base grows, we decide to scale-out
the search service and our Rails-based application. This is done via a
REST call to the Marathon API to add more tasks. Marathon will take care of
placing the new tasks on machines with spare capacity, honoring the
constraints we previously set.

<p class="text-center">
  <img src="{{ site.baseurl}}/img/marathon2.png" width="420" height="269" alt="">
</p>

Imagine that one of the datacenter workers trips over a power cord and a server
gets unplugged. No problem for Marathon, it moves the affected search service
and Rails tasks to a node that has spare capacity. The engineer may be
temporarily embarrassed, but Marathon saves him from having to explain a
difficult situation!

<p class="text-center">
  <img src="{{ site.baseurl}}/img/marathon3.png" width="417" height="268" alt="">
</p>
