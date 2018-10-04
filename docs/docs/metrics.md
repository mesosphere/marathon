---
title: Metrics
---

# Metrics

Marathon uses [Dropwizard Metrics](https://github.com/dropwizard/metrics)
for its metrics. You can query the current metric values via the
`/metrics` HTTP endpoint.

For the specific syntax see the
[metrics command-line flags]({{ site.baseurl }}/docs/command-line-flags.html#metrics-flags)
section.

## Stability of metric names

Although we try to prevent unnecessary disruptions, we do not provide
stability guarantees for metric names between major and minor releases.

## Metric types

Marathon has the following metric types:

* a `counter` is a monotonically increasing integer, for instance, the
  number of Mesos `revive` calls performed since Marathon became
  a leader.
* a `gauge` is a current measurement, for instance, the number of apps
  currently known to Marathon.
* a `histogram` is a distribution of values in a stream of measurements,
  for instance, the number of apps in group deployments.
* a `meter` measures the rate at which a set of events occur.
* a `timer` is a combination of a meter and a histogram, which measure
  the duration of events and the rate of their occurrence.

Histograms and timers are backed with reservoirs leveraging
[HdrHistogram](http://hdrhistogram.org/).

## Units of measurement

A metric measures something either in abstract quantities, or in the
following units:

* `bytes`
* `seconds`

## Metric names

All metric names are prefixed with `marathon` by default. The prefix can
be changed using `--metrics_prefix` command-line flag.

Metric name components are joined with dots. Components may have dashes
in them.

A metric type and a unit of measurement (if any) are appended to
a metric name. A couple of examples:

* `marathon.apps.active.gauge`
* `marathon.http.event-streams.responses.size.counter.bytes`

## Prometheus reporter

The Prometheus reporter is enabled by default, and it can be disabled
with `--disable_metrics_prometheus` command-line flag. Metrics in the
Prometheus format are available at `/metrics/prometheus`.

Dots and dashes in metric names are replaced with underscores.

## StatsD reporter

The StatsD reporter can be enabled with `--metrics_statsd` command-line
flag. It sends metrics over UDP to the host and port specified with
`--metrics_statsd_host` and `--metrics_statsd_port` respectively.

Counters are forwarded as gauges.

## DataDog reporter

The DataDog reporter can be enabled with `--metrics_datadog`
command-line flag. It sends metrics over UDP to the host and port
specified with `--metrics_datadog_host` and `--metrics_datadog_port`
respectively.

Marathon can send metrics to a DataDog agent over UDP, or directly to
the DataDog cloud over HTTP. It is specified using
`--metrics_datadog_protocol`. Its possible values are `udp` (default)
and `api`. If `api` is chosen, your DataDog API key can be supplied with
`--metrics_datadog_api_key`.

Dashes in metric names are replaced with underscores. Counters are
forwarded as gauges.

## Important metrics

* `marathon.apps.active.gauge` — the number of active apps.
* `marathon.deployments.active.gauge` — the number of active
  deployments.
* `marathon.deployments.counter` — the count of deployments received
  since the current Marathon instance became a leader.
* `marathon.deployments.dismissed.counter` — the count of deployments
  dismissed since the current Marathon instance became a leader;
  a deployment might be dismissed by Marathon, when there are too many
  concurrent deployments.
* `marathon.groups.active.gauge` — the number of active groups.
* `marathon.instances.running.gauge` — the number of running instances
  at the moment.
* `marathon.instances.staged.gauge` — the number of instances staged at
  the moment.
* `marathon.leadership.duration.gauge.seconds` — the duration of
  current leadership.
* `marathon.persistence.gc.runs.counter` — the count of Marathon GC runs
  since it became a leader.
* `marathon.persistence.gc.compaction.duration.timer.seconds` —
  a histogram of Marathon GC compaction phase durations, and a meter for
  compaction durations.
* `marathon.persistence.gc.scan.duration.timer.seconds` — a histogram of
  Marathon GC scan phase durations, and a meter for scan durations.
* `marathon.pods.active.gauge` — the number of active pods.
* `marathon.tasks.launched.counter` — the count of tasks launched by
  the current Marathon instance since it became a leader.
* `marathon.uptime.gauge.seconds` — uptime of the current Marathon
  instance.
* `marathon.instances.overdue.gauge` - the number of overdue instances. Overdue 
  instances are those that has been either launched or staged but hasn't 
  become running within the `task_launch_timeout` or `task_launch_confirmation_timeout` 
  respectively.
* `marathon.instances.doomed.gauge` - the current number of instances for which 
  Marathon has sent a kill request but hasn't received a confirmation from 
  Mesos yet.
* `instances.doomed.kill-attempts.gauge` - the total number of kill attempts that
  Marathon has sent for all currently existing doomed instances.
  

### Mesos-specific metrics

* `marathon.tasks.launched.counter` — the count of Mesos tasks
  launched by the current Marathon instance since it became a leader.
* `marathon.mesos.calls.revive.counter` — the count of Mesos `revive`
  calls made since the current Marathon instance became a leader.
* `marathon.mesos.calls.suppress.counter` — the count of Mesos
  `suppress` calls made since the current Marathon instance became
  a leader.
* `marathon.mesos.offer-operations.launch-group.counter` — the count of
  `LaunchGroup` offer operations made since the current Marathon
  instance became a leader.
* `marathon.mesos.offer-operations.launch.counter` — the count of
  `Launch` offer operations made since the current Marathon instance
  became a leader.
* `marathon.mesos.offer-operations.reserve.counter` — the count of
  `Reserve` offer operations made since the current Marathon instance
  became a leader.
* `marathon.mesos.offers.declined.counter` — the count of offers
  declined since the current Marathon instance became a leader.
* `marathon.mesos.offers.incoming.counter` — the count of offers
  received since the current Marathon instance became a leader.
* `marathon.mesos.offers.used.counter` — the count of offers used since
  the current Marathon instance became a leader.
* `marathon.mesos.task-updates.<task-state>.counter` — the count of task
  status updates received per each task state (`task-running`,
  `task-failed` and so on).

### HTTP-specific metrics

* `marathon.http.responses.event-stream.size.counter.bytes` — the size
  of data sent to clients over event streams since the current Marathon
  instance became a leader.
* `marathon.http.requests.size.counter.bytes` — the total size of
  all requests since the current Marathon instance became a leader.
* `marathon.http.responses.size.counter.bytes` — the total size of all
  responses since the current Marathon instance became a leader.
* `marathon.http.responses.size.gzipped.counter.bytes` — the total size
  of all gzipped responses since the current Marathon instance became
  a leader.
* `marathon.http.requests.active.gauge` — the number of active requests.
* `marathon.http.responses.1xx.rate.meter` — the rate of `1xx` responses.
* `marathon.http.responses.2xx.rate.meter` — the rate of `2xx` responses.
* `marathon.http.responses.3xx.rate.meter` — the rate of `3xx` responses.
* `marathon.http.responses.4xx.rate.meter` — the rate of `4xx` responses.
* `marathon.http.responses.5xx.rate.meter` — the rate of `5xx` responses.
* `marathon.http.requests.duration.timer.seconds` — a histogram of
  request durations, and a meter for request durations.
* `marathon.http.requests.get.duration.timer.seconds` — the same but for
  `GET` requests only.
* `marathon.http.requests.post.duration.timer.seconds` — the same but
  for `POST` requests only.
* `marathon.http.requests.put.duration.timer.seconds` — the same but for
  `PUT` requests only.
* `marathon.http.requests.delete.duration.timer.seconds` — the same but
  for `DELETE` requests only.

### JVM-specific metrics

#### JVM buffer pools

* `marathon.jvm.buffers.mapped.gauge` — an estimate of the number of
  mapped buffers.
* `marathon.jvm.buffers.mapped.capacity.gauge.bytes` — an estimate of
  the total capacity of the mapped buffers in bytes.
* `marathon.jvm.buffers.mapped.memory.used.gauge.bytes` an estimate of
  the memory that the JVM is using for mapped buffers in bytes, or `-1L`
  if an estimate of the memory usage is not available.
* `marathon.jvm.buffers.direct.gauge` — an estimate of the number of
  direct buffers.
* `marathon.jvm.buffers.direct.capacity.gauge.bytes` — an estimate of
  the total capacity of the direct buffers in bytes.
* `marathon.jvm.buffers.direct.memory.used.gauge.bytes` an estimate of
  the memory that the JVM is using for direct buffers in bytes, or `-1L`
  if an estimate of the memory usage is not available.

#### JVM garbage collection

* `marathon.jvm.gc.<gc>.collections.gauge` — the total number
  of collections that have occurred
* `marathon.jvm.gc.<gc>.collections.duraration.gauge.seconds` — the
  approximate accumulated collection elapsed time, or `-1` if the
  collection elapsed time is undefined for the given collector.

#### JVM memory

* `marathon.jvm.memory.total.init.gauge.bytes` - the amount of memory
  in bytes that the JVM initially requests from the operating system
  for memory management, or `-1` if the initial memory size is
  undefined.
* `marathon.jvm.memory.total.used.gauge.bytes` - the amount of used
  memory in bytes.
* `marathon.jvm.memory.total.max.gauge.bytes` - the maximum amount of
  memory in bytes that can be used for memory management, `-1` if the
  maximum memory size is undefined.
* `marathon.jvm.memory.total.committed.gauge.bytes` - the amount of
  memory in bytes that is committed for the JVM to use.
* `marathon.jvm.memory.heap.init.gauge.bytes` - the amount of heap
  memory in bytes that the JVM initially requests from the operating
  system for memory management, or `-1` if the initial memory size is
  undefined.
* `marathon.jvm.memory.heap.used.gauge.bytes` - the amount of used heap
  memory in bytes.
* `marathon.jvm.memory.heap.max.gauge.bytes` - the maximum amount of
  heap memory in bytes that can be used for memory management, `-1` if
  the maximum memory size is undefined.
* `marathon.jvm.memory.heap.committed.gauge.bytes` - the amount of heap
  memory in bytes that is committed for the JVM to use.
* `marathon.jvm.memory.heap.usage.gauge` - the ratio of
  `marathon.jvm.memory.heap.used.gauge.bytes` and
  `marathon.jvm.memory.heap.max.gauge.bytes`.
* `marathon.jvm.memory.non-heap.init.gauge.bytes` - the amount of
  non-heap memory in bytes that the JVM initially requests from the
  operating system for memory management, or `-1` if the initial memory
  size is undefined.
* `marathon.jvm.memory.non-heap.used.gauge.bytes` - the amount of used
  non-heap memory in bytes.
* `marathon.jvm.memory.non-heap.max.gauge.bytes` - the maximum amount of
  non-heap memory in bytes that can be used for memory management, `-1`
  if the maximum memory size is undefined.
* `marathon.jvm.memory.non-heap.committed.gauge.bytes` - the amount of
  non-heap memory in bytes that is committed for the JVM to use.
* `marathon.jvm.memory.non-heap.usage.gauge` - the ratio of
  `marathon.jvm.memory.non-heap.used.gauge.bytes` and
  `marathon.jvm.memory.non-heap.max.gauge.bytes`.

#### JVM threads

* `marathon.jvm.threads.active.gauge` — the number of active threads.
* `marathon.jvm.threads.daemon.gauge` — the number of daemon threads.
* `marathon.jvm.threads.deadlocked.gauge` — the number of deadlocked
  threads.
* `marathon.jvm.threads.new.gauge` — the number of threads in `NEW`
   state.
* `marathon.jvm.threads.runnable.gauge` — the number of threads in
  `RUNNABLE` state.
* `marathon.jvm.threads.blocked.gauge` — the number of threads in
  `BLOCKED` state.
* `marathon.jvm.threads.timed-waiting.gauge` — the number of threads in
  `TIMED_WAITING` state.
* `marathon.jvm.threads.waiting.gauge` — the number of threads in
  `WAITING` state.
* `marathon.jvm.threads.terminated.gauge` — the number of threads in
  `TERMINATED` state.

## Alerting

Let's consider a few examples of alerting rules. We will use Prometheus
as our alerting tool, but please keep in mind that any alerting service
of your choice can be used instead.

Please note that particular threshold values are dependent on many
variables like the number of applications, a cluster size and so on.

For the syntax of Prometheus alerting rules please refer to
[its official documentation](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/).

Alert whenever a Marathon instance goes down or restarts:

```yaml
  - alert: Marathon Uptime
    expr: up{job="marathon"} == 0
    for: 30s
    labels:
      severity: warning
    annotations:
      summary: It fires when a Marathon instance stops or restarts.
```

Alert when there are less than 2 out of 3 Marathon instances that are
up and running:

```yaml
  - alert: Marathon Too Few Instances
    expr: sum(up{job="marathon"}) < 2
    for: 30s
    labels:
      severity: critical
      annotations:
      summary: It fires when there are fewer Marathon instances up and running than expected (< 2 instances).
```

Alert if Marathon's heap usage is too high:

```yaml
  - alert: Marathon Heap Usage
    expr: avg_over_time(marathon_jvm_memory_heap_used_gauge_bytes[10s]) > (2 * 1024 * 1024 * 1024)
    for: 10s
    labels:
      severity: critical
    annotations:
      summary: It fires when Marathon's heap is too big (> 2GB).
```

Alert if Marathon has too many threads:

```yaml
  - alert: Marathon Threads
    expr: avg_over_time(marathon_jvm_threads_active_gauge[10s]) > 100
    for: 10s
    labels:
      severity: critical
    annotations:
      summary: It fires when Marathon has too many threads (> 100 threads).
```

Alert if Marathon dismisses deployments way too often:

```yaml
  - alert: Marathon Dismissed Deployments
    expr: rate(marathon_deployments_dismissed_counter[1m]) > 3
    for: 1m
    labels:
      severity: warning
    annotations:
      summary: It fires if there are too many deployments dismissed by Marathon (> 3 deployments over the last minute).
```

Alert if Marathon receives offers, but is unable to use them for some
reason, i.e. insufficient resources:

```yaml
  - alert: Marathon Given No Suitable Offer
    expr: rate(marathon_mesos_offers_used_counter[10m]) == 0 and rate(marathon_mesos_offers_incoming_counter[10m]) > 0
    for: 10m
    labels:
      severity: warning
    annotations:
      summary: It fires if there are incoming Mesos offers, but none can be used by Marathon (for the last 10 minutes).
```

Alert if there are too many concurrent HTTP requests to Marathon:

```yaml
  - alert: Marathon HTTP Requests
    expr: avg_over_time(marathon_http_requests_active_gauge[10s]) > 100
    for: 10s
    labels:
      severity: critical
    annotations:
      summary: It fires if there are too many concurrent HTTP requests to Marathon (> 100 concurrent connections).
```

Alert if Marathon responds with a 5xx status code:

```yaml
  - alert: Marathon HTTP 5xx Responses
    expr: marathon_http_responses_5xx_rate_m1_rate_meter > 0
    for: 10s
    labels:
      severity: critical
    annotations:
      summary: It fires if Marathon responds with a 5xx status codes.
```

Alert if Marathon has too many staged instances, which might indicate
that Marathon does not receive offers with enough resources or

```yaml
  - alert: Marathon Too Many Staged Instances
    expr: marathon_instances_staged_gauge > 10
    for: 10s
    labels:
      severity: warning
    annotations:
      summary: It fires if Marathon has too many staged instances (> 10 staged instances).
```
