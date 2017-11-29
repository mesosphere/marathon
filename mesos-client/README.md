# Mesos V1 Client
Mesos V1 Client is a Scala implementation of mesos v1 scheduler API as described [here](http://mesos.apache.org/documentation/latest/scheduler-http-api/). It heavily utilizes [akka-http](https://doc.akka.io/docs/akka-http/current/scala/http/) and [akka-streams](https://doc.akka.io/docs/akka/2.5.4/scala/stream/index.html) libraries and provides mesos source and mesos sink as two basic abstractions for inbound and outbound communication with mesos.

## Mesos source
Mesos source is an akka-stream `Source[Event, NotUsed]` that frameworks can attach to, to receive mesos event. Basic flow visualized looks like following

```
 ------------
| Http       | (1)
| Connection |
 ------------
      |
      v
 ------------
| Connection | (2)
| Handler    |     -> updates connection context
 ------------
      |
      v
 ------------
| Data Bytes | (3)
| Extractor  |
 ------------
      |
      v
 ------------
| RecordIO   | (4)
| Scanner    |
 ------------
      |
      v
 --------------
| Event        | (5)
| Deserializer |
 --------------
      |
      v
 --------------
| Subscribed   | (6)  -> updates connection context
| Handler      |
 --------------
      |
      v
 --------------
| BroadcastHub | (6)
 --------------
  |  |  |  |  |
  v  v  v  v  v
```

1. **Http Connection** mesos-v1-client uses akka-http low-level `Http.outgoingConnection()` to `POST` a [SUBSCRIBE](http://mesos.apache.org/documentation/latest/scheduler-http-api/#subscribe-1) request to mesos `api/v1/scheduler` endpoint providing framework info. The HTTP response is a stream in RecordIO format which is handled by the later stages
2. **Connection Handler** handles connection HTTP response, saving `Mesos-Stream-Id`(see the description of the [SUBSCRIBE](http://mesos.apache.org/documentation/latest/scheduler-http-api/#subscribe-1) call) in client's _connection context_ object to later use it in mesos sink. Schedulers are expected to make HTTP requests to the leading master. If requests are made to a non-leading master a `HTTP 307 Temporary Redirect` will be received with the `Location` header pointing to the leading master. We update connection context with the new leader address and throw a `MesosRedicrectException` which is handled it in the `recover` stage by building a new flow that reconnects to the new leader
3. **Data Byte Extractor** simply extracts byte data from the response body
4. **RecordIO Scanner** Each stream message is encoded in RecordIO format, which essentially prepends to a single record (either JSON or serialized protobuf) its length in bytes: `[<length>\n<json string|protobuf bytes>]`. More about the format [here](http://mesos.apache.org/documentation/latest/scheduler-http-api/#recordio-response-format-1). RecordIO Scanner uses `RecordIOFraming.Scanner` from the [alpakka-library](https://github.com/akka/alpakka) to parse the extracted bytes into a complete message frame
5. **Event Deserializer** Currently mesos-v1-client only supports protobuf encoded events/calls. Event deserializer uses [scalapb](https://scalapb.github.io/) library to parse the extracted RecordIO frame from the previous stage into a mesos [Event](https://github.com/apache/mesos/blob/master/include/mesos/scheduler/scheduler.proto#L36)
6. **Subscribed Handler** parses the `SUBSCRIBED` event and updates the connection context with the returned `frameworkId`.
7. **BroadcastHub** ad the end the flow is going through a broadcast hub. This allows for a dynamic "fan-out" streaming of events and avoids making multiple upstream connections to mesos when the source is materialized by multiple event consumers

Note: _Connection Handler_ updates the _connection context_ object with current leader's `host` and `port` along with `Mesos-Stream-Id` header value. These settings are used by mesos sink when sending calls to mesos. The same applies to _Subscribed Handler_ which saves `frameworkId` from the `SUBSCRIBED` event in the connection context.

## Mesos Sink
Mesos sink is an akka-stream `Sink[Call, Notused]` that sends calls to mesos. Every call is send via a new (and pooled) connection. The flow visualized:

```
   |  |  |
   v  v  v
  ----------
 | MergeHub | (1)
  ----------
      |
      v
 ------------
| Call       |
| Enhancer   | (2)
 ------------
     |
     v
 ------------
| Event      |
| Serializer | (3)
 ------------
      |
      v
 ------------
| Request    |
| Builder    | (4)
 ------------
      |
      v
 ------------
| Http Sink  | (5)
 ------------
```
1. **MergeHub** allows dynamic "fan-in" junction point for mesos calls from multiple producers
2. **Call Enhancer** updates the mesos call with the framework Id from the connection context
3. **Event Serializer** serializes calls to byte array
4. **Request Builder** builds a HTTP request from the data using `mesosStreamId` header from the context
5. **Http Sink** creates a new connection using akka's `Http().singleRequest` and sends the data

Note: Merge hub will wait for the _connection context_ object to be fully initialized first meaning that we have current leader's `host`, `port` and `Mesos-Stream-Id` to send the events to.

## Kill Switch
A kill switch can be used to forcefully shutdown the mesos source. Calling `shutdown()` or `abort()` on it will close the connection to mesos. Note that depending on `failoverTimeout` provided with `SUBSCRIBED` call mesos could start killing tasks and executors started by the framework. Make sure to set `failoverTimeout` appropriately. See `teardown()` method for another way to shutdown a framework.

## Mesos API Trait
Mesos API trait has three main methods: `mesosSource` for inbound events, `mesosSink` for outbound calls and `killSwitch` to forcefully disconnect from mesos.

```
trait MesosApi {

  /**
    * First call to this method will initialize the connection to mesos and return a `Source[String, NotUser]` with
    * mesos `Event`s. All subsequent calls will return the previously created event source.
    */
  def mesosSource: Source[Event, NotUsed]

  /**
    * Sink for mesos calls. Multiple publishers can materialize this sink to send mesos `Call`s. Every `Call` is sent
    * using a new HTTP connection.
    */
  def mesosSink: Sink[Call, NotUsed]

  /**
    * A kill switch for the mesos source. Calling `shutdown()` or `abort()` on it will close the connection to mesos.
    */
  val killSwitch: UniqueKillSwitch
  ```

  It also provides a number of helper methods to build mesos calls e.g. `def accept(accepts: Accept): Call` will return an `Accept` call.
