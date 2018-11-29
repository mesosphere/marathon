---
title: Event Bus
---

# Events

## Deprecations

From 1.8 onwards `v2/events` will only output lightweight events. See the [changelog entry](https://github.com/mesosphere/marathon/blob/master/changelog.md#v2events) for details.

## What is this concept? Why or when would I use it?

Marathon publishes events that capture all API requests, scaling events and updates. It is the preferred way over pulling
to be informed on Marathon's state. The event endpoint is useful for integrating with any entity that acts based on the
state of Marathon, like load balancers, or to compile statistics. 

## How can I use it?

Marathon implements [Server-Sent-Events (SSE)](https://en.wikipedia.org/wiki/Server-sent_events) standard. Events are
published on `/v2/events` endpoint. Any SSE-compatible client can subscribe.

Example subscription using `curl`:

```bash
$ curl -H "Accept: text/event-stream"  <MARATHON_HOST>:<MARATHON_PORT>/v2/events

event: event_stream_attached
data: {"remoteAddress":"127.0.0.1","eventType":"event_stream_attached","timestamp":"2017-02-18T19:12:00.102Z"}
```

### Filtering the Event Stream

Starting from version [1.3.7](https://github.com/mesosphere/marathon/releases/tag/v1.3.7),
Marathon supports filtering the event stream by event type.
To filter by event type,
specify a value for the `event_type` parameter in your `/v2/events` request.
This could be done by adding interesting event type as value for `event_type`
parameter to `/v2/events` request.

The following example only subscribes to events involving a new client
attaching to or detaching from the event stream.

```bash
curl -H "Accept: text/event-stream"  <MARATHON_HOST>:<MARATHON_PORT>/v2/events?event_type=event_stream_detached\&event_type=event_stream_attached
```

All event types can be found in [Events.scala](https://github.com/mesosphere/marathon/blob/master/src/main/scala/mesosphere/marathon/core/event/Events.scala).

## Which related concepts should I understand?

The published events cover [instance](???), [app](???) and [pod](...) changes as well as [deployments](???) and [health checks](???).
They usually refer to affected entities by id.

## Are there any limitations or things to consider?

Marathon buffers up to `event_stream_max_outstanding_messages` (see [command line arguments](???)) messages per consumer.
If the consumer does not read the messages fast enough the connection will be dropped. Consumers are urged to handle these
cases. They should try to reconnect or [fail loud and proud](code-culture.md).

[Filtering events](#filtering-the-event-stream) will reduce the load on consumers and Marathon.

## Related Topics
 * Instances
 * Deployments
 * Tooling
 * API
