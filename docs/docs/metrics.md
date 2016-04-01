---
title: Metrics
---

# Metrics

Marathon currently uses [Codahale/Dropwizard Metrics](https://github.com/dropwizard/metrics). You can query
the current metrics via the `/metrics` HTTP endpoint or configure the metrics to report periodically to:

* graphite via `--reporter_graphite`.
* datadog via `--reporter_datadog`.
* statsd via `--reporter_datadog` (datadog reports supports statsd).

For the specific syntax see the
[command line flag metrics]({{ site.baseurl }}/docs/command-line-flags.html#metrics-flags) section.

## Stability of metric names

Although we try to prevent unnecessary disruptions, we do not provide stability guarantees for metric names between major and minor releases.

We will not change the name of a metric non-method-call (see below) metric in a patch release if this is not required to fix a production issue, which is very unusual.

## Metric names

All metric names have to prefixed by a prefix that you specify and are subject to modification by graphite, datadog, or statsd. For example, if we write that the name of a metric is `service.mesosphere.marathon.uptime`, it might be available under `stats.gauges.marathon_test.service.mesosphere.marathon.uptime` in your configuration.

## Important metrics

`service.mesosphere.marathon.uptime` (gauge) - The uptime of the reporting Marathon process in milliseconds. This is helpful to diagnose stability problems that cause Marathon to restart.

### App, group, and task counts
`service.mesosphere.marathon.leaderDuration` (gauge) - The duration since the last leader election happened
in milliseconds. This is helpful to diagnose stability problems and how often leader election happens.

`service.mesosphere.marathon.app.count` (gauge) - The number of defined apps. This number influences the performance of Marathon: if you have
a high number of apps, your performance will be lower than for a low number of
apps.

`service.mesosphere.marathon.group.count` (gauge) - The number of defined groups. This number influences the performance of Marathon: if you have a high number of groups, your performance will be lower than for a low number of groups. Note that each term between the slashes in your an ID corresponds to a group. The app `/shop/frontend` is in the `frontend` group, which is in the `shop` group, which is in the root group.

<span class="label label-default">v0.15</span>
`service.mesosphere.marathon.task.running.count` (gauge) - The number of tasks that are
currently running.

<span class="label label-default">v0.15</span> 
`service.mesosphere.marathon.task.staged.count` (gauge) - The number of tasks that are
currently staged. Tasks enter staging state after they are launched. A consistently high number of staged tasks indicates that a lot of tasks are stopping and being restarted. Either you have many app updates/manual restarts or some of your apps have stability problems and are automatically restarted frequently.

### Task update processing

<span class="label label-default">v0.15</span>
`service.mesosphere.marathon.core.task.update.impl.ThrottlingTaskStatusUpdateProcessor.queued` (gauge) - The number of queued status updates.

<span class="label label-default">v0.15</span>
`service.mesosphere.marathon.core.task.update.impl.ThrottlingTaskStatusUpdateProcessor.processing` (gauge) - The number of status updates currently being processed.
 
 <span class="label label-default">v0.15</span>
 `service.mesosphere.marathon.core.task.update.impl.TaskStatusUpdateProcessorImpl.publishFuture` (timer) - This metric calculates how long it takes Marathon to process status updates.

<span class="label label-default">v0.15</span>
`service.mesosphere.marathon.core.task.update.impl.TaskStatusUpdateProcessorImpl.publishFuture` (timer) - This metric calculates how long it takes Marathon to process status updates.

### Configuration update processing

<span class="label label-default">v0.15</span>
`service.mesosphere.marathon.state.GroupManager.queued` (gauge) - The number of app configuration updates in the queue. Use `--max_queued_root_group_updates` to configure the maximum.

<span class="label label-default">v0.15</span>
`service.mesosphere.marathon.state.GroupManager.processing` (gauge) - The number of currently processed app configuration updates. Since we serialize these updates, this is either 0 or 1.

### Repositories

Marathon stores its permanent state in "repositories." The important ones are:

* `GroupRepository` for app configurations and groups.
* `TaskRepository` for the last known task state. This is the repository with the largest data churn.

Other repositories include:

* `AppRepository` for versioned app configuration.
* `DeploymentRepository` for currently running deployments.
* `TaskFailureRepository` for the last failure for every application.

We have statistics about read and write requests for each repository. To access them, substitute `*` with the name of a repository:

`service.mesosphere.marathon.state.*.read-request-time.count` - The number of read requests.

`service.mesosphere.marathon.state.*.read-request-time.mean` - The exponential weighted average of the read request times.

`service.mesosphere.marathon.state.*.write-request-time.count` - The number of write requests.

`service.mesosphere.marathon.state.*.write-request-time.mean` - The [exponential weighted average](https://dropwizard.github.io/metrics/3.1.0/manual/core/#exponentially-decaying-reservoirs) of the write request times.

**Note:** Many of the repository metrics were not measured correctly prior to <span class="label label-default">v0.15</span>.

### Requests

`org.eclipse.jetty.servlet.ServletContextHandler.dispatches` (timer) - The
number of HTTP requests received by Marathon is available under `.count`.
There are more metrics around HTTP requests under the
`org.eclipse.jetty.servlet.ServletContextHandler` prefix.
For more information, consult [the code](https://github.com/dropwizard/metrics/blob/796663609f310888240cc8afb58f75396f8391d2/metrics-jetty9/src/main/java/io/dropwizard/metrics/jetty9/InstrumentedHandler.java#L41-L42).

### JVM

`jvm.threads.count` (meter) - The total number of threads. This number should be below 500.

`jvm.memory.total.used` (meter) - The total number of bytes used by the Marathon JVM.

## Instrumented method calls

These metrics are created automatically by instrumenting certain classes in our code base.

You can disable these instrumented metrics with `--disable_metrics`. This flag will only disable this code instrumentation, not all metrics.

These timers can be very valuable in diagnosing problems, but they require detailed knowledge of the inner workings of Marathon. They can also degrade performance noticeably.

Since these metric names directly correspond to class and method names in our code base,
expect the names of these metrics to change if the affected code changes.

## Potential pitfalls

### Derived metrics (mean, p99, ...)

Our metrics library calculates derived metrics like "mean" and "p99." However, Marathon provides these metrics for the entire life of of the app and applies an exponential weighting algorithm as a heuristic. For more precise metrics, build your dashboard around "counts" rather than "rates" where possible.

### Statsd and derived statistics

Statsd typically creates derived statistics (mean, p99) from mean values Marathon reports. Our codahale metrics package also reports derived statistics. To avoid accidentally aggregating statistics multiple times, be sure you know where you are reporting and computing mean values.
