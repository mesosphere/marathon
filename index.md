---
title: A cluster-wide init and control system for services in cgroups or Docker containers
---

<div class="jumbotron text-center">
  <h1>Marathon</h1>
  <p class="lead">
    A cluster-wide init and control system for services in cgroups or Docker containers
  </p>
  <p>
    <a href="http://downloads.mesosphere.com/marathon/v0.7.3/marathon-0.7.3.tgz"
        class="btn btn-lg btn-primary">
      Download Marathon v0.7.3
    </a>
  </p>
  <a class="btn btn-link"
      href="http://downloads.mesosphere.com/marathon/v0.7.1/marathon-0.7.3.tgz.sha256">
    v0.7.3 SHA-256 Checksum
  </a> &middot;
  <a class="btn btn-link"
      href="https://github.com/mesosphere/marathon/releases/tag/v0.7.3">
    v0.7.3 Release Notes
  </a>
</div>

## Overview

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
temporarily embarrased, but Marathon saves him from having to explain a
difficult situation!

<p class="text-center">
  <img src="{{ site.baseurl}}/img/marathon3.png" width="417" height="268" alt="">
</p>
