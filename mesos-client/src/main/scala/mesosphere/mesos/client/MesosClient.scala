package mesosphere.mesos.client

import akka.stream.{ Materializer, OverflowStrategy }
import java.net.URI

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
import mesosphere.mesos.conf.MesosClientConf
import org.apache.mesos.v1.mesos._
import org.apache.mesos.v1.scheduler.scheduler.Call.{ Accept, Acknowledge, Decline, Kill, Message, Reconcile, Revive }
import org.apache.mesos.v1.scheduler.scheduler.{ Call, Event }
import scala.concurrent.{ ExecutionContext, Future, Promise }

// TODO - move somewhere shared
trait StrictLoggingFlow extends StrictLogging {
  protected def log[T](prefix: String): Flow[T, T, NotUsed] = Flow[T].map{ e => logger.info(s"$prefix$e"); e }
}

trait MesosApi {
  /**
    * Calling shutdown()` or `abort()` on this will close the connection to Mesos.
    *
    * Note that depending on `failoverTimeout` provided with SUBSCRIBED call, Mesos could start killing tasks and
    * executors started by the framework. Make sure to set `failoverTimeout` appropriately.
    *
    *  See `teardown()` Call factory method for another way to shutdown a framework.
    */
  def killSwitch: KillSwitch

  // format: OFF
  /**
    * Source which exposes a live, suspended Mesos event stream (after the first Subscribed event). Can only be
    * materialized once. If you need to fan-out to multiple, look at BroadcastHub, FlowOps.alsoTo, or the GraphDSL
    * Broadcast node.
    *
    * The original connection is initialized with a `POST /api/v1/scheduler` with the framework info in the body. The
    * request is answered by a `SUBSCRIBED` event which contains `MesosStreamId` header. This is reused by all later
    * calls to `/api/v1/scheduler`.
    *
    * This source will be closed either on connection error or connection shutdown e.g.:
    * ```
    * client.mesosSource.runWith(Sink.ignore).onComplete{
    *  case Success(res) => logger.info(s"Stream completed: $res")
    *  case Failure(e) => logger.error(s"Error in stream: $e")
    * }
    * ```
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#subscribe-1
    *
    * The topology for the Mesos Source looks some like this:
    *
    * +------------+           +----------------+
    * | Http       | (1)  -->  | ConnectionInfo | (2)
    * | Connection |           | Handler        |
    * +------------+           +----------------+
    * |
    * v
    * +------------+
    * | Data Bytes | (3)
    * | Extractor  |
    * +------------+
    * |
    * v
    * +------------+
    * | RecordIO   | (4)
    * | Scanner    |
    * +------------+
    * |
    * v
    * +--------------+
    * | Event        | (5)
    * | Deserializer |
    * +--------------+
    * |
    * v
    * +------------+      +------------+
    * + Broadcast  + -->  | Subscribed | (6)
    * +------------+      | Watcher    |
    * |                   +------------+
    * v
    * +--------------+
    * | Event        | (7)
    * | Publisher    |
    * +--------------+
    * |
    * v
    * +-----------------+
    * | Your logic here |
    * +-----------------+
    *
    *
    *
    * 1. Http Connection mesos-v1-client uses akka-http low-level `Http.outgoingConnection()` to `POST` a
    * [SUBSCRIBE](http://mesos.apache.org/documentation/latest/scheduler-http-api/#subscribe-1) request to mesos `api/v1/scheduler`
    * endpoint providing framework info. The HTTP response is a stream in RecordIO format which is handled by the later stages.
    *
    * 2. Connection Handler handles connection HTTP response, saving `Mesos-Stream-Id`(see the description of the
    * [SUBSCRIBE](http://mesos.apache.org/documentation/latest/scheduler-http-api/#subscribe-1) call) in client's _connection context_
    * object to later use it in Mesos sink. Schedulers are expected to make HTTP requests to the leading master. If requests are made
    * to a non-leading master a `HTTP 307 Temporary Redirect` will be received with the `Location` header pointing to the leading master.
    * We update connection context with the new leader address and throw a `MesosRedirectException` which is handled it in the `recover`
    * stage by building a new flow that reconnects to the new leader.
    *
    * 3. Data Byte Extractor simply extracts byte data from the response body
    *
    * 4. RecordIO Scanner Each stream message is encoded in RecordIO format, which essentially prepends to a single record (either JSON or
    * serialized protobuf) its length in bytes: `[<length>\n<json string|protobuf bytes>]`. More about the format
    * [here](http://mesos.apache.org/documentation/latest/scheduler-http-api/#recordio-response-format-1). RecordIO Scanner uses
    * `RecordIOFraming.Scanner` from the [alpakka-library](https://github.com/akka/alpakka) to parse the extracted bytes into a complete
    * message frame.
    *
    * 5. Event Deserializer Currently mesos-v1-client only supports protobuf encoded events/calls. Event deserializer uses
    * [scalapb](https://scalapb.github.io/) library to parse the extracted RecordIO frame from the previous stage into a mesos
    * [Event](https://github.com/apache/mesos/blob/master/include/mesos/scheduler/scheduler.proto#L36)
    *
    * 6. Subscribed Handler parses the `SUBSCRIBED` event and provides it to the constructed MesosClient for later reference.
    *
    * 7. Event Publisher is a single-use materializable source and does not include the initial SUBSCRIBED event. The
    * entire Mesos event stream is backpressured until this source is materialized.
    */
  def mesosSource: Source[Event, NotUsed]

  /**
    * mesosSink is an akka-stream `Sink[Call, Notused]` that sends Mesos `Call`s events to Mesos. Every call is sent via
    * the same long-living connection to Mesos. This way we save resources and guarantee message order delivery.
    *
    * It is expected that Calls are constructed using the MesosCalls methods exposed via the MesosClient instance. These
    * methods return Calls with the frameworkId field populated.
    *
    * Each materialization results in a new HTTP connection. If you need multiple producers to reuse a single
    * connection, see MergeHub, FlowOps.merge, or GraphDSL Merge node.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#calls
    *
    * The flow visualized:
    *
    * |  |  |
    * v  v  v
    * +------------+
    * | Event      |
    * | Serializer | (1)
    * +------------+
    * |
    * v
    * +------------+
    * | Request    |
    * | Builder    | (2)  <-- reads mesosStreamId and from connection context
    * +------------+
    * |
    * v
    * +------------+
    * | Http       |
    * | Connection | (3)  <-- reads mesos url from connection context
    * +------------+
    * |
    * v
    * +------------+
    * | Response   |
    * | Handler    | (4)
    * +------------+
    *
    * 1. Event Serializer serializes calls to byte array
    * 2. Build a HTTP request from the data using `mesosStreamId` header from the context
    * 3. Http connection uses akka's `Http().outgoingConnection` to sends the data to mesos. Note that all calls are sent
    * through one long-living connection.
    * 4. Response handler will discard response entity or throw an exception on non-2xx response code
    *
    * Note: the materialized Future[Done] will be completed successfully if your upstream completes, or, will be
    * completed with an error if the HTTP connection is terminated, or an upstream error is propagated.
    */
  def mesosSink: Sink[Call, Future[Done]]
}

trait MesosCalls {
  val frameworkId: FrameworkID
  private def someFrameworkId = Some(frameworkId)
  /**
    * ***************************************************************************
    * Helper methods to create mesos `Call`s
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#calls
    * ***************************************************************************
    */

  /**
    * Factory method to construct a TEARDOWN Mesos Call event. Calling this method has no side effects.
    *
    * This event is sent by the scheduler when it wants to tear itself down. When Mesos receives this request it will
    * shut down all executors (and consequently kill tasks). It then removes the framework and closes all open
    * connections from this scheduler to the Master.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#teardown
    */
  def teardown(): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.TEARDOWN)
    )
  }

  /**
    * Factory method to construct a ACCEPT Mesos Call event. Calling this method has no side effects.
    *
    * Sent by the scheduler when it accepts offer(s) sent by the master. The ACCEPT request includes the type
    * of operations (e.g., launch task, launch task group, reserve resources, create volumes) that the scheduler
    * wants to perform on the offers. Note that until the scheduler replies (accepts or declines) to an offer,
    * the offer’s resources are considered allocated to the offer’s role and to the framework.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#accept
    */
  def accept(accepts: Accept): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.ACCEPT),
      accept = Some(accepts))
  }

  /**
    * Factory method to construct a DECLINE Mesos Call event. Calling this method has no side effects.
    *
    * Sent by the scheduler to explicitly decline offer(s) received. Note that this is same as sending an ACCEPT
    * call with no operations.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#decline
    */
  def decline(offerIds: Seq[OfferID], filters: Option[Filters] = None): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.DECLINE),
      decline = Some(Decline(offerIds = offerIds, filters = filters))
    )
  }

  /**
    * Factory method to construct a REVIVE Mesos Call event. Calling this method has no side effects.
    *
    * Sent by the scheduler to remove any/all filters that it has previously set via ACCEPT or DECLINE calls.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#revive
    */
  def revive(role: Option[String] = None): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.REVIVE),
      revive = Some(Revive(role = role))
    )
  }

  /**
    * Factory method to construct a SUPPRESS Mesos Call event. Calling this method has no side effects.
    *
    * Suppress offers for the specified roles. If `roles` is empty the `SUPPRESS` call will suppress offers for all
    * of the roles the framework is currently subscribed to.
    *
    * http://mesos.apache.org/documentation/latest/upgrades/#1-2-x-revive-suppress
    */
  def suppress(roles: Option[String] = None): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.SUPPRESS),
      suppress = Some(Call.Suppress(roles))
    )
  }

  /**
    * Factory method to construct a KILL Mesos Call event. Calling this method has no side effects.
    *
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
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.KILL),
      kill = Some(Kill(taskId = taskId, agentId = agentId, killPolicy = killPolicy))
    )
  }

  /**
    * Factory method to construct a SHUTDOWN Mesos Call event. Calling this method has no side effects.
    *
    * Sent by the scheduler to shutdown a specific custom executor. When an executor gets a shutdown event, it is
    * expected to kill all its tasks (and send TASK_KILLED updates) and terminate.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#shutdown
    */
  def shutdown(executorId: ExecutorID, agentId: AgentID): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.SHUTDOWN),
      shutdown = Some(Call.Shutdown(executorId = executorId, agentId = agentId))
    )
  }

  /**
    * Factory method to construct a ACKNOWLEDGE Mesos Call event. Calling this method has no side effects.
    *
    * Sent by the scheduler to acknowledge a status update. Note that with the new API, schedulers are responsible
    * for explicitly acknowledging the receipt of status updates that have status.uuid set. These status updates
    * are retried until they are acknowledged by the scheduler. The scheduler must not acknowledge status updates
    * that do not have `status.uuid` set, as they are not retried. The `uuid` field contains raw bytes encoded in Base64.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#acknowledge
    */
  def acknowledge(agentId: AgentID, taskId: TaskID, uuid: protobuf.ByteString): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.ACKNOWLEDGE),
      acknowledge = Some(Acknowledge(agentId, taskId, uuid))
    )
  }

  /**
    * Factory method to construct a RECONCILE Mesos Call event. Calling this method has no side effects.
    *
    * Sent by the scheduler to query the status of non-terminal tasks. This causes the master to send back UPDATE
    * events for each task in the list. Tasks that are no longer known to Mesos will result in TASK_LOST updates.
    * If the list of tasks is empty, master will send UPDATE events for all currently known tasks of the framework.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#reconcile
    */
  def reconcile(tasks: Seq[Reconcile.Task]): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.RECONCILE),
      reconcile = Some(Reconcile(tasks))
    )
  }

  /**
    * Factory method to construct a MESSAGE Mesos Call event. Calling this method has no side effects.
    *
    * Sent by the scheduler to send arbitrary binary data to the executor. Mesos neither interprets this data nor
    * makes any guarantees about the delivery of this message to the executor. data is raw bytes encoded in Base64
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#message
    */
  def message(agentId: AgentID, executorId: ExecutorID, message: ByteString): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.MESSAGE),
      message = Some(Message(agentId, executorId, protobuf.ByteString.copyFrom(message.toByteBuffer)))
    )
  }

  /**
    * Factory method to construct a REQUEST Mesos Call event. Calling this method has no side effects.
    *
    * Sent by the scheduler to request resources from the master/allocator. The built-in hierarchical allocator
    * simply ignores this request but other allocators (modules) can interpret this in a customizable fashion.
    *
    * http://mesos.apache.org/documentation/latest/scheduler-http-api/#request
    */
  def request(requests: Seq[Request]): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.REQUEST),
      request = Some(Call.Request(requests = requests))
    )
  }

  /**
    * Factory method to construct a ACCEPT_INVERSE_OFFERS Mesos Call event. Calling this method has no side effects.
    *
    * Accepts an inverse offer. Inverse offers should only be accepted if the resources in the offer can be safely
    * evacuated before the provided unavailability.
    *
    * https://mesosphere.com/blog/mesos-inverse-offers/
    */
  def acceptInverseOffers(offers: Seq[OfferID], filters: Option[Filters] = None): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.ACCEPT_INVERSE_OFFERS),
      acceptInverseOffers = Some(Call.AcceptInverseOffers(inverseOfferIds = offers, filters = filters))
    )
  }

  /**
    * Factory method to construct a DECLINE_INVERSE_OFFERS Mesos Call event. Calling this method has no side effects.
    *
    * Declines an inverse offer. Inverse offers should be declined if
    * the resources in the offer might not be safely evacuated before
    * the provided unavailability.
    *
    * https://mesosphere.com/blog/mesos-inverse-offers/
    */
  def declineInverseOffers(offers: Seq[OfferID], filters: Option[Filters] = None): Call = {
    Call(
      frameworkId = someFrameworkId,
      `type` = Some(Call.Type.DECLINE_INVERSE_OFFERS),
      declineInverseOffers = Some(Call.DeclineInverseOffers(inverseOfferIds = offers, filters = filters))
    )
  }
}

// TODO: Add more integration tests

object MesosClient extends StrictLogging {
  case class MesosRedirectException(leader: URI) extends Exception(s"New mesos leader available at $leader")

  case class ConnectionInfo(url: URI, streamId: String)

  val MesosStreamIdHeaderName = "Mesos-Stream-Id"
  def MesosStreamIdHeader(streamId: String) = headers.RawHeader("Mesos-Stream-Id", streamId)
  val ProtobufMediaType: MediaType.Binary = MediaType.applicationBinary("x-protobuf", Compressible)

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

  private[client] def log[T](prefix: String): Flow[T, T, NotUsed] = Flow[T].map{ e => logger.info(s"$prefix$e"); e }

  private val dataBytesExtractor: Flow[HttpResponse, ByteString, NotUsed] =
    Flow[HttpResponse].flatMapConcat(resp => resp.entity.dataBytes)

  private val eventDeserializer: Flow[ByteString, Event, NotUsed] =
    Flow[ByteString].map(bytes => Event.parseFrom(bytes.toArray))

  private def connectionSource(frameworkInfo: FrameworkInfo, url: URI)(implicit mat: Materializer, as: ActorSystem) = {
    val body = subscribe(frameworkInfo).toByteArray

    val request = HttpRequest(
      HttpMethods.POST,
      uri = Uri("/api/v1/scheduler"),
      entity = HttpEntity(ProtobufMediaType, body),
      headers = List(headers.Accept(ProtobufMediaType)))

    val httpConnection = Http().outgoingConnection(url.getHost, url.getPort)
    Source.single(request)
      .via(log(s"Connecting to the new leader: ${url}"))
      .via(httpConnection)
      .via(log("HttpResponse: "))
  }

  /**
    * Input events (Call) are sent to the scheduler, serially, with backpressure. Events received from Mesos are
    * received accordingly.
    */
  def connect(conf: MesosClientConf, frameworkInfo: FrameworkInfo)(
    implicit
    system: ActorSystem, materializer: ActorMaterializer, executionContext: ExecutionContext): RunnableGraph[Future[MesosClient]] = {

    val subscribedWatcher: Sink[Event, Future[Event.Subscribed]] = Flow[Event].collect {
      case event if event.subscribed.isDefined =>
        event.subscribed.get
      case o =>
        throw new RuntimeException(s"Expected subscribed event, got ${o}")
    }.toMat(Sink.head)(Keep.right)

    def mesosHttpConnection(url: URI, redirectRetries: Int): Source[(HttpResponse, ConnectionInfo), NotUsed] =
      connectionSource(frameworkInfo, url)
        .map { response =>
          response.status match {
            case StatusCodes.OK =>
              logger.info(s"Connected successfully to ${url}");
              val streamId = response.headers
                .find(h => h.is(MesosStreamIdHeaderName.toLowerCase))
                .getOrElse(throw new IllegalStateException(s"Missing MesosStreamId header in ${response.headers}"))

              (response, ConnectionInfo(url, streamId.value()))
            case StatusCodes.TemporaryRedirect =>
              val leader = new URI(response.header[headers.Location].get.value())
              logger.warn(s"New mesos leader available at $leader")
              // Update the context with the new leader's host and port and throw an exception that is handled in the
              // next `recoverWith` stage.
              response.discardEntityBytes()
              throw new MesosRedirectException(leader)
            case _ =>
              response.discardEntityBytes()
              throw new IllegalArgumentException(s"Mesos server error: $response")
          }
        }
        .recoverWithRetries(conf.redirectRetries, {
          case MesosRedirectException(leader) => mesosHttpConnection(leader, redirectRetries)
        })

    val initialUrl = new java.net.URI(s"http://${conf.master}")

    val httpConnection = mesosHttpConnection(initialUrl, conf.redirectRetries)

    val eventReader = Flow[HttpResponse]
      .flatMapConcat(_.entity.dataBytes)
      .via(RecordIOFraming.scanner())
      .via(eventDeserializer)
      .via(log("Received mesos Event: "))
      .idleTimeout(conf.idleTimeout)
      .buffer(conf.sourceBufferSize, OverflowStrategy.backpressure)

    val eventsOutputSink = Sink.asPublisher[Event](false)

    val graph = GraphDSL.create(
      httpConnection, subscribedWatcher, Sink.head[ConnectionInfo], KillSwitches.single[Event], eventsOutputSink)(
      { (_, subscribedF, connectionInfoF, killSwitch, eventsOutputPublisher) =>
        for {
          subscribed <- subscribedF
          connectionInfo <- connectionInfoF,
        } yield {
          MesosClient(killSwitch, subscribed, connectionInfo, Source.fromPublisher(eventsOutputPublisher))
        }
      }
    ) { implicit b =>
        { (httpConnectionShape, subscribedWatchedShape, connectionInfoWatcher, killSwitch, eventsOutputSinkShape) =>
          import GraphDSL.Implicits._

          val unzip = b.add(Unzip[HttpResponse, ConnectionInfo])
          val eventBroadcast = b.add(Broadcast[Event](2, eagerCancel = false))

          // Wire output events
          httpConnectionShape ~> unzip.in
          unzip.out0.via(eventReader) ~> killSwitch ~> eventBroadcast
          unzip.out1 ~> connectionInfoWatcher

          eventBroadcast.out(0).take(1) ~> subscribedWatchedShape
          /* we use detach to prevent a deadlock situation. "drop(1)" does not initiate a pull, so we use detach to
           * preemptively pull an element so that the first broadcast output can receive an element. */
          eventBroadcast.out(1).drop(1).detach ~> eventsOutputSinkShape
          ClosedShape
        }
      }

    RunnableGraph.fromGraph(graph)
  }
}

/**
  *
  */
case class MesosClient(
  killSwitch: KillSwitch,
  subscribed: Event.Subscribed,
  connectionInfo: MesosClient.ConnectionInfo,
  /**
    * Events from Mesos scheduler, sans initial Subscribed event.
    */
  mesosSource: Source[Event, NotUsed])(
    implicit
    as: ActorSystem, m: Materializer) extends MesosApi with MesosCalls with StrictLoggingFlow {

  val frameworkId = subscribed.frameworkId

  private val responseHandler: Sink[HttpResponse, Future[Done]] = Sink.foreach[HttpResponse] { response =>
    response.status match {
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
      uri = Uri(s"${connectionInfo.url}/api/v1/scheduler"),
      entity = HttpEntity(MesosClient.ProtobufMediaType, bytes),
      headers = List(MesosClient.MesosStreamIdHeader(connectionInfo.streamId)))
    )

  def httpConnection: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
    Http().outgoingConnection(connectionInfo.url.getHost, connectionInfo.url.getPort)

  override val mesosSink: Sink[Call, Future[Done]] =
    Flow[Call]
      .via(log("Sending "))
      .via(eventSerializer)
      .via(requestBuilder)
      .via(httpConnection)
      .toMat(responseHandler)(Keep.right)
}
