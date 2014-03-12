package mesosphere.marathon.health

import akka.actor._
import mesosphere.marathon.api.v1.{HealthCheckProtocol, HealthCheckDefinition}
import scala.collection.mutable
import mesosphere.marathon.api.v1.Implicits.DurationToFiniteDuration
import mesosphere.marathon.MarathonSchedulerService
import spray.httpx.RequestBuilding.Get
import mesosphere.marathon.tasks.TaskTracker
import scala.concurrent.Future
import spray.client.pipelining._
import spray.http.HttpRequest
import spray.http.HttpResponse

object HealthCheckMessage extends Enumeration {
  val AddAll = Value
  val RemoveAll = Value
}

object HealthCheckMessagePayload extends Enumeration {
  val Insert = Value
  val Remove = Value
}

abstract class HealthMessagePacketBase
case class HealthMessagePacket(
  message: HealthCheckMessage.Value
) extends HealthMessagePacketBase

case class HealthCheckPayload(appID: String, healthCheckDef: HealthCheckDefinition)
case class HealthMessagePacketPayload(
  message: HealthCheckMessagePayload.Value,
  payload: HealthCheckPayload
) extends HealthMessagePacketBase

case class HealthCheckTick(payload: HealthCheckPayload) extends HealthMessagePacketBase

case class HealthActorProxy(actor: ActorRef){
  def sendMessage(msg: HealthMessagePacketBase): Unit = actor ! msg
}

object HealthActorData {
  case class HealthCheckDescriptor(healthCheckDef: HealthCheckDefinition,
                                   cancellable: Cancellable)

  val healthChecks = mutable.Map[String, Set[HealthCheckDescriptor]]().withDefaultValue(
    Set.empty[HealthCheckDescriptor])
}

case class HealthActor(system: ActorSystem, service: MarathonSchedulerService, taskTracker: TaskTracker)
    extends Actor with ActorLogging {

  import system.dispatcher // for ExecutionContext

  val pipeline: HttpRequest => Future[HttpResponse] = (
    addHeader("Accept", "application/json")
      ~> sendReceive)

  val healthChecks = HealthActorData.healthChecks

  private def insertHealthCheck(payload: HealthCheckPayload): Unit = {
    val existing = healthChecks(payload.appID)
    if (!existing.map(_.healthCheckDef).contains(payload.healthCheckDef)) {

      val cancellable =
        system.scheduler.schedule(payload.healthCheckDef.initialDelay,
          payload.healthCheckDef.interval,
          self,
          HealthCheckTick(payload))

      healthChecks(payload.appID) = existing +
        HealthActorData.HealthCheckDescriptor(payload.healthCheckDef, cancellable)
    }
  }

  private def removeHealthCheck(appID: String, healthCheckDef: HealthCheckDefinition): Unit = {
    val existing = healthChecks(appID)
    if (existing.map(_.healthCheckDef).contains(healthCheckDef)) {
      val descriptor = existing.find(_.healthCheckDef == healthCheckDef).get
      descriptor.cancellable.cancel()

      healthChecks(appID) = existing - descriptor
    }
  }

  private def removeAllHealthChecks(): Unit = for {
      (appID, descriptors) <- healthChecks
      descriptor <- descriptors
    } removeHealthCheck(appID, descriptor.healthCheckDef)

  private def addAllHealthChecks(): Unit = for {
    app <- service.listApps()
    healthCheck <- app.healthChecks
  } insertHealthCheck(HealthCheckPayload(app.id, healthCheck))

  private def doHealthCheck(tick: HealthCheckTick): Unit = {
    val healthCheckDef = tick.payload.healthCheckDef
    assert(healthCheckDef.protocol == HealthCheckProtocol.HttpHealthCheck)

    val appDef = service.getApp(tick.payload.appID).get
    val app = taskTracker.fetchApp(tick.payload.appID)

    val uris = app.tasks.map(task =>
      s"${task.getHost}:${appDef.ports(healthCheckDef.portIndex)}/${healthCheckDef.path}")

    val futures = for (uri <- uris) yield {
      log.info("Sending GET to:" + uri)
      for (res <- pipeline(Get(uri))) yield (uri, res)
    }

    Future.sequence(futures).onComplete { t =>
      val statuses = t.get.map(res =>
        (res._1, res._2.status.intValue,
          healthCheckDef.acceptableResponses.contains(res._2.status.intValue)))
      val failures = statuses.filter(_._3)
      failures.foreach(f =>
        log.error(s"Health check failed for app.id: ${tick.payload.appID}: path: ${f._1}. status: ${f._2}"))
      if (failures.isEmpty) log.info(s"Health check passed for app.id: ${tick.payload.appID}")
    }
  }

  def receive = {
    case packet: HealthMessagePacket => packet.message match {
      case HealthCheckMessage.AddAll    => addAllHealthChecks()
      case HealthCheckMessage.RemoveAll => removeAllHealthChecks()
    }
    case packet: HealthMessagePacketPayload => packet.message match {
      case HealthCheckMessagePayload.Insert => insertHealthCheck(packet.payload)
      case HealthCheckMessagePayload.Remove =>
        removeHealthCheck(packet.payload.appID, packet.payload.healthCheckDef)
    }
    case tick: HealthCheckTick => doHealthCheck(tick)
  }
}
