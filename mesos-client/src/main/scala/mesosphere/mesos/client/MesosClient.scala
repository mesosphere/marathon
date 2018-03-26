package mesosphere.mesos.client

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.MediaType.Compressible
import akka.http.scaladsl.model._
import akka.stream._
import akka.stream.alpakka.recordio.scaladsl.RecordIOFraming
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.{ Done, NotUsed }
import com.google.protobuf
import com.typesafe.scalalogging.StrictLogging
import mesosphere.mesos.client.MesosClient.{ MesosRedirectException, ProtobufMediaType, MesosStreamIdHeaderName }
import mesosphere.mesos.conf.MesosClientConf
import org.apache.mesos.v1.mesos._
import org.apache.mesos.v1.scheduler.scheduler.Call.{ Accept, Acknowledge, Decline, Kill, Message, Reconcile, Revive }
import org.apache.mesos.v1.scheduler.scheduler.{ Call, Event }
import scala.concurrent.{ ExecutionContext, Future, Promise }

class MesosClient(
    conf: MesosClientConf,
    frameworkInfo: FrameworkInfo)(
    implicit
    val system: ActorSystem,
    implicit val materializer: ActorMaterializer,
    implicit val executionContext: ExecutionContext
) extends MesosApi with StrictLogging {

  private val overflowStrategy = akka.stream.OverflowStrategy.backpressure

  val context = new AtomicReference[MesosConnectionContext](MesosConnectionContext(conf))
  val contextPromise = Promise[MesosConnectionContext]()

  // format: OFF
  /**
    Mesos source is an akka-stream `Source[Event, UniqueKillSwitch]` that frameworks can attach to, to receive mesos event.
    Basic flow visualized looks like following

     ------------
    | Http       | (1)
    | Connection |
     ------------
          |
          v
     ------------
    | Connection | (2)
    | Handler    |     -> updates connection context with mesos streamId
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
    | Subscribed   | (6)  -> updates connection context with frameworkId
    | Handler      |
     --------------
          |
          v


    1. Http Connection mesos-v1-client uses akka-http low-level `Http.outgoingConnection()` to `POST` a
       [SUBSCRIBE](http://mesos.apache.org/documentation/latest/scheduler-http-api/#subscribe-1) request to mesos `api/v1/scheduler`
       endpoint providing framework info. The HTTP response is a stream in RecordIO format which is handled by the later stages.
    2. Connection Handler handles connection HTTP response, saving `Mesos-Stream-Id`(see the description of the
       [SUBSCRIBE](http://mesos.apache.org/documentation/latest/scheduler-http-api/#subscribe-1) call) in client's _connection context_
       object to later use it in mesos sink. Schedulers are expected to make HTTP requests to the leading master. If requests are made
       to a non-leading master a `HTTP 307 Temporary Redirect` will be received with the `Location` header pointing to the leading master.
       We update connection context with the new leader address and throw a `MesosRedicrectException` which is handled it in the `recover`
       stage by building a new flow that reconnects to the new leader.
    3. Data Byte Extractor simply extracts byte data from the response body
    4. RecordIO Scanner Each stream message is encoded in RecordIO format, which essentially prepends to a single record (either JSON or
       serialized protobuf) its length in bytes: `[<length>\n<json string|protobuf bytes>]`. More about the format
       [here](http://mesos.apache.org/documentation/latest/scheduler-http-api/#recordio-response-format-1). RecordIO Scanner uses
       `RecordIOFraming.Scanner` from the [alpakka-library](https://github.com/akka/alpakka) to parse the extracted bytes into a complete
       message frame.
    5. Event Deserializer Currently mesos-v1-client only supports protobuf encoded events/calls. Event deserializer uses
       [scalapb](https://scalapb.github.io/) library to parse the extracted RecordIO frame from the previous stage into a mesos
       [Event](https://github.com/apache/mesos/blob/master/include/mesos/scheduler/scheduler.proto#L36)
    6. Subscribed Handler parses the `SUBSCRIBED` event and updates the connection context with the returned `frameworkId`.

    Note: _Connection Handler_ updates the _connection context_ object with current mesos leader `host` and `port` along with `Mesos-Stream-Id`
    header value. These settings are used by mesos sink when sending calls to mesos. The same applies to _Subscribed Handler_ which saves
    `frameworkId` from the `SUBSCRIBED` event in the connection context.

    It is declared lazy to decouple instantiation of the MesosClient instance from connection initialization.
    */
  // format: ON
  def log[T](prefix: String): Flow[T, T, NotUsed] = Flow[T].map{ e => logger.info(s"$prefix$e"); e }

  val mesosSource: Source[Event, UniqueKillSwitch] = {

    val body = subscribe(frameworkInfo).toByteArray

    val request = HttpRequest(
      HttpMethods.POST,
      uri = Uri("/api/v1/scheduler"),
      entity = HttpEntity(ProtobufMediaType, body),
      headers = List(headers.Accept(ProtobufMediaType)))

    def httpConnection(host: String, port: Int): Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnection(host, port)

    val recordIoScanner: Flow[ByteString, ByteString, NotUsed] = RecordIOFraming.scanner()

    val connectionHandler: Flow[HttpResponse, HttpResponse, NotUsed] = Flow[HttpResponse].map { response =>
      response.status match {
        case StatusCodes.OK =>
          logger.info(s"Connected successfully to ${context.get().url}");
          val streamId = response.headers
            .find(h => h.is(MesosStreamIdHeaderName.toLowerCase))
            .getOrElse(throw new IllegalStateException(s"Missing MesosStreamId header in ${response.headers}"))

          // At this point we successfully connected to mesos leader so context should have correct leader's host
          // and port either from the config or set on the redirect.
          context.updateAndGet(c => c.copy(streamId = Some(streamId.value())))
          response
        case StatusCodes.TemporaryRedirect =>
          val leader = new URI(response.header[headers.Location].get.value())
          logger.warn(s"New mesos leader available at $leader")
          // Update the context with the new leader's host and port and throw an exception that is handled in the
          // next `recoverWith` stage.
          context.updateAndGet(c => c.copy(url = leader))
          response.discardEntityBytes()
          throw new MesosRedirectException(leader)
        case _ =>
          response.discardEntityBytes()
          throw new IllegalArgumentException(s"Mesos server error: $response")
      }
    }

    val dataBytesExtractor: Flow[HttpResponse, ByteString, NotUsed] = Flow[HttpResponse].flatMapConcat(resp => resp.entity.dataBytes)

    val eventDeserializer: Flow[ByteString, Event, NotUsed] = Flow[ByteString].map(bytes => Event.parseFrom(bytes.toArray))

    val subscribedHandler: Flow[Event, Event, NotUsed] = Flow[Event].map { event =>
      if (event.subscribed.isDefined) {
        val ctx = context.updateAndGet(c => c.copy(frameworkId = Some(event.subscribed.get.frameworkId)))
        // We save `frameworkId` in the context and successfully complete the promise, meaning the calls in sink can now be sent
        contextPromise.success(ctx)
      }
      event
    }

    def connectionSource(host: String, port: Int) =
      Source.single(request)
        .via(log(s"Connecting to the new leader: $host:$port"))
        .via(httpConnection(host, port))
        .via(log("HttpResponse: "))
        .via(connectionHandler)

    val source = connectionSource(context.get().host, context.get().port)
      .recoverWithRetries(conf.redirectRetires, {
        case MesosRedirectException(_) => connectionSource(context.get().host, context.get().port)
      })
      .buffer(conf.sourceBufferSize, overflowStrategy)
      .via(dataBytesExtractor)
      .via(recordIoScanner)
      .via(eventDeserializer)
      .via(subscribedHandler)
      .via(log("Received mesos Event: "))
      .idleTimeout(conf.idleTimeout)
      .viaMat(KillSwitches.single)(Keep.right)

    source
  }

  // format: OFF
  /**
  Mesos sink is an akka-stream `Sink[Call, Notused]` that sends calls to mesos. Every call is sent via the same long-living
  connection to mesos. This way we save resources and guarantee message order delivery. The flow visualized:

       |  |  |
       v  v  v
      ----------
     | MergeHub | (1)
      ----------
          |
          v
     ------------
    | Call       |
    | Enhancer   | (2)  <-- reads frameworkId from connection context
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
    | Builder    | (4)  <-- reads mesosStreamId and from connection context
     ------------
          |
          v
     ------------
    | Http       |
    | Connection | (5)  <-- reads mesos url from connection context
     ------------
          |
          v
     ------------
    | Response   |
    | Handler    | (6)
     ------------

    1. MergeHub allows dynamic "fan-in" junction point for mesos calls from multiple producers.
    2. Call Enhancer updates the call with the framework Id from the connection context
    3. Event Serializer serializes calls to byte array
    4. Build a HTTP request from the data using `mesosStreamId` header from the context
    5. Http connection uses akka's `Http().outgoingConnection` to sends the data to mesos. Note that all calls are sent
       through one long-living connection.
    6. Response handler will discard response entity or throw an exception on non-2xx response code

    Note: Merge hub will wait for the _connection context_ object to be fully initialized first meaning that we have current leader's
    `host`, `port` and `Mesos-Stream-Id` to send the events to.
    */
  // format: ON

  // Initially `Http().singleRequest` was used to send calls. However when calls are fired at a high-speed rate,
  // it might quickly overflow small http pool (the pool is shared for the entire actor system). Backpressure is not
  // applied here.
  //
  // `Http().superPool()` or `Http.cachedHostConnectionPool()` are an alternative. However it may happen there that the requests
  // sent could arrive at mesos out of sent order! The native scheduler driver guarantees that the events will be received by
  // mesos in the same order they were sent.
  //
  // `Http().outgoingConnection` will create one long-living connection and use it to send events to mesos. Backpressure is applied
  // automatically when mesos is slow and events are in order.
  private val httpConnection: Flow[HttpRequest, HttpResponse, NotUsed] = Flow[HttpRequest]
    .via(Http().outgoingConnection(context.get().host, context.get().port))

  private val responseHandler: Sink[HttpResponse, Future[Done]] = Sink.foreach[HttpResponse] { response =>
    response status match {
      case status if status.isFailure() =>
        logger.info(s"A request to mesos failed with response: ${response}")
        response.discardEntityBytes()
        throw new IllegalStateException(s"Failed to send a call to mesos")
      case _ =>
        logger.debug(s"Mesos call response: $response")
        response.discardEntityBytes()
    }
  }

  private val eventSerializer: Flow[Call, Array[Byte], NotUsed] = Flow[Call]
    .map(call => call.toByteArray)

  private val requestBuilder: Flow[Array[Byte], HttpRequest, NotUsed] = Flow[Array[Byte]]
    .map(bytes => HttpRequest(
      HttpMethods.POST,
      uri = Uri(s"${context.get().url}/api/v1/scheduler"),
      entity = HttpEntity(ProtobufMediaType, bytes),
      headers = List(MesosClient.MesosStreamIdHeader(context.get().streamId.getOrElse(throw new IllegalStateException("MesosStreamId not set.")))))
    )

  private val callEnhancer: Flow[Call, Call, NotUsed] = Flow[Call]
    .map { call =>
      call.update(
        _.optionalFrameworkId := Some(context.get().frameworkId.getOrElse(throw new IllegalStateException("FrameworkID not set")))
      )
    }

  private val sinkHub: RunnableGraph[Sink[Call, NotUsed]] =
    MergeHub.source[Call](perProducerBufferSize = 16)
      .mapAsync(1)(event => contextPromise.future.map(_ => event))
      .via(callEnhancer)
      .via(log("Sending "))
      .via(eventSerializer)
      .via(requestBuilder)
      .via(httpConnection)
      .to(responseHandler)

  // By running/materializing the consumer we get back a Sink, and hence now have access to feed elements into it.
  // This Sink can be materialized any number of times, and every element that enters the Sink will be consumed by
  // our consumer.
  override val mesosSink: Sink[Call, NotUsed] = sinkHub.run()
}

trait MesosApi {

  /**
    * First call to this method will initialize the connection to mesos and return a `Source[String, NotUsed]` with
    * mesos `Event`s. All subsequent calls will return the previously created event source.
    * The connection is initialized with a `POST /api/v1/scheduler` with the framework info in the body. The request
    * is answered by a `SUBSCRIBED` event which contains `MesosStreamId` header. This is reused by all later calls to
    * `/api/v1/scheduler`.
    * Multiple subscribers can attach to returned `Source[String, NotUsed]` to receive mesos events. The stream will
    * be closed either on connection error or connection shutdown e.g.:
    * ```
    * client.source.runWith(Sink.ignore).onComplete{
    *  case Success(res) => logger.info(s"Stream completed: $res")
    *  case Failure(e) => logger.error(s"Error in stream: $e")
    * }
    * ```
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#subscribe-1
    *
    * A materialized value of the source is a kill switch. Calling shutdown()` or `abort()` on it will close the connection to mesos.
    * Note that depending on `failoverTimeout` provided with SUBSCRIBED call mesos could start killing tasks and
    * executors started by the framework. Make sure to set `failoverTimeout` appropriately. See `teardown()` method
    * for another way to shutdown a framework.
    */
  def mesosSource: Source[Event, UniqueKillSwitch]

  /**
    * Sink for mesos calls. Multiple publishers can materialize this sink to send mesos `Call`s. Every `Call` Every call is
    * sent via one long-living connection to mesos.
    * Note: a scheduler can't send calls to mesos without subscribing first (see [MesosClient.source] method). Calls
    * published to sink without a successful subscription will be buffered and will have to wait for subscription
    * connection. Always call `source()` first.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#calls
    */
  def mesosSink: Sink[Call, NotUsed]

  /**
    * ***************************************************************************
    * Helper methods to create mesos `Call`s
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#calls
    * ***************************************************************************
    */

  /**
    * This is the first step in the communication process between the scheduler and the master. This is also to be
    * considered as subscription to the “/scheduler” event stream. To subscribe with the master, the scheduler sends
    * an HTTP POST with a SUBSCRIBE message including the required FrameworkInfo. Note that if
    * `subscribe.framework_info.id` is not set, master considers the scheduler as a new one and subscribes it by
    * assigning it a FrameworkID. The HTTP response is a stream in RecordIO format; the event stream begins with a
    * SUBSCRIBED event.
    *
    * Note: this method is used by mesos client to establish connection to mesos master and is not supposed to be called
    * directly by the framework.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#subscribe-1
    */
  protected def subscribe(frameworkInfo: FrameworkInfo): Call = {
    Call(
      frameworkId = frameworkInfo.id,
      subscribe = Some(Call.Subscribe(frameworkInfo)),
      `type` = Some(Call.Type.SUBSCRIBE)
    )
  }

  /**
    * Sent by the scheduler when it wants to tear itself down. When Mesos receives this request it will
    * shut down all executors (and consequently kill tasks). It then removes the framework and closes all
    * open connections from this scheduler to the Master.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#teardown
    */
  def teardown(): Call = {
    Call(
      `type` = Some(Call.Type.TEARDOWN)
    )
  }

  /**
    * Sent by the scheduler when it accepts offer(s) sent by the master. The ACCEPT request includes the type
    * of operations (e.g., launch task, launch task group, reserve resources, create volumes) that the scheduler
    * wants to perform on the offers. Note that until the scheduler replies (accepts or declines) to an offer,
    * the offer’s resources are considered allocated to the offer’s role and to the framework.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#accept
    */
  def accept(accepts: Accept): Call = {
    Call(
      `type` = Some(Call.Type.ACCEPT),
      accept = Some(accepts))
  }

  /**
    * Sent by the scheduler to explicitly decline offer(s) received. Note that this is same as sending an ACCEPT
    * call with no operations.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#decline
    */
  def decline(offerIds: Seq[OfferID], filters: Option[Filters] = None): Call = {
    Call(
      `type` = Some(Call.Type.DECLINE),
      decline = Some(Decline(offerIds = offerIds, filters = filters))
    )
  }

  /**
    * Sent by the scheduler to remove any/all filters that it has previously set via ACCEPT or DECLINE calls.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#revive
    */
  def revive(role: Option[String] = None): Call = {
    Call(
      `type` = Some(Call.Type.REVIVE),
      revive = Some(Revive(role = role))
    )
  }

  /**
    * Suppress offers for the specified roles. If `roles` is empty the `SUPPRESS` call will suppress offers for all
    * of the roles the framework is currently subscribed to.
    *
    * http://mesos.apache.org/documentation/latest/upgrades/#1-2-x-revive-suppress
    */
  def suppress(roles: Option[String] = None): Call = {
    Call(
      `type` = Some(Call.Type.SUPPRESS),
      suppress = Some(Call.Suppress(roles))
    )
  }

  /**
    * Sent by the scheduler to kill a specific task. If the scheduler has a custom executor, the kill is forwarded
    * to the executor; it is up to the executor to kill the task and send a TASK_KILLED (or TASK_FAILED) update.
    * If the task hasn’t yet been delivered to the executor when Mesos master or agent receives the kill request,
    * a TASK_KILLED is generated and the task launch is not forwarded to the executor. Note that if the task belongs
    * to a task group, killing of one task results in all tasks in the task group being killed. Mesos releases the
    * resources for a task once it receives a terminal update for the task. If the task is unknown to the master,
    * a TASK_LOST will be generated.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#kill
    */
  def kill(taskId: TaskID, agentId: Option[AgentID] = None, killPolicy: Option[KillPolicy]): Call = {
    Call(
      `type` = Some(Call.Type.KILL),
      kill = Some(Kill(taskId = taskId, agentId = agentId, killPolicy = killPolicy))
    )
  }

  /**
    * Sent by the scheduler to shutdown a specific custom executor. When an executor gets a shutdown event, it is
    * expected to kill all its tasks (and send TASK_KILLED updates) and terminate.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#shutdown
    */
  def shutdown(executorId: ExecutorID, agentId: AgentID): Call = {
    Call(
      `type` = Some(Call.Type.SHUTDOWN),
      shutdown = Some(Call.Shutdown(executorId = executorId, agentId = agentId))
    )
  }

  /**
    * Sent by the scheduler to acknowledge a status update. Note that with the new API, schedulers are responsible
    * for explicitly acknowledging the receipt of status updates that have status.uuid set. These status updates
    * are retried until they are acknowledged by the scheduler. The scheduler must not acknowledge status updates
    * that do not have `status.uuid` set, as they are not retried. The `uuid` field contains raw bytes encoded in Base64.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#acknowledge
    */
  def acknowledge(agentId: AgentID, taskId: TaskID, uuid: protobuf.ByteString): Call = {
    Call(
      `type` = Some(Call.Type.ACKNOWLEDGE),
      acknowledge = Some(Acknowledge(agentId, taskId, uuid))
    )
  }

  /**
    * Sent by the scheduler to query the status of non-terminal tasks. This causes the master to send back UPDATE
    * events for each task in the list. Tasks that are no longer known to Mesos will result in TASK_LOST updates.
    * If the list of tasks is empty, master will send UPDATE events for all currently known tasks of the framework.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#reconcile
    */
  def reconcile(tasks: Seq[Reconcile.Task]): Call = {
    Call(
      `type` = Some(Call.Type.RECONCILE),
      reconcile = Some(Reconcile(tasks))
    )
  }

  /**
    * Sent by the scheduler to send arbitrary binary data to the executor. Mesos neither interprets this data nor
    * makes any guarantees about the delivery of this message to the executor. data is raw bytes encoded in Base64
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#message
    */
  def message(agentId: AgentID, executorId: ExecutorID, message: ByteString): Call = {
    Call(
      `type` = Some(Call.Type.MESSAGE),
      message = Some(Message(agentId, executorId, protobuf.ByteString.copyFrom(message.toByteBuffer)))
    )
  }

  /**
    * Sent by the scheduler to request resources from the master/allocator. The built-in hierarchical allocator
    * simply ignores this request but other allocators (modules) can interpret this in a customizable fashion.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#request
    */
  def request(requests: Seq[Request]): Call = {
    Call(
      `type` = Some(Call.Type.REQUEST),
      request = Some(Call.Request(requests = requests))
    )
  }

  /**
    * Accepts an inverse offer. Inverse offers should only be accepted if the resources in the offer can be safely
    * evacuated before the provided unavailability.
    *
    * https://mesosphere.com/blog/mesos-inverse-offers/
    */
  def acceptInverseOffers(offers: Seq[OfferID], filters: Option[Filters] = None): Call = {
    Call(
      `type` = Some(Call.Type.ACCEPT_INVERSE_OFFERS),
      acceptInverseOffers = Some(Call.AcceptInverseOffers(inverseOfferIds = offers, filters = filters))
    )
  }

  /**
    * Declines an inverse offer. Inverse offers should be declined if
    * the resources in the offer might not be safely evacuated before
    * the provided unavailability.
    *
    * https://mesosphere.com/blog/mesos-inverse-offers/
    */
  def declineInverseOffers(offers: Seq[OfferID], filters: Option[Filters] = None): Call = {
    Call(
      `type` = Some(Call.Type.DECLINE_INVERSE_OFFERS),
      declineInverseOffers = Some(Call.DeclineInverseOffers(inverseOfferIds = offers, filters = filters))
    )
  }

}

// TODO: Add more integration tests

object MesosClient {
  case class MesosRedirectException(leader: URI) extends Exception(s"New mesos leader available at $leader")

  val MesosStreamIdHeaderName = "Mesos-Stream-Id"
  def MesosStreamIdHeader(streamId: String) = headers.RawHeader("Mesos-Stream-Id", streamId)
  val ProtobufMediaType: MediaType.Binary = MediaType.applicationBinary("x-protobuf", Compressible)
}
