package mesosphere.marathon.health

import akka.actor._
import mesosphere.marathon.api.v1.{AppDefinition, HealthCheckProtocol, HealthCheckDefinition}
import scala.collection.mutable
import mesosphere.marathon.api.v1.Implicits.DurationToFiniteDuration
import spray.httpx.RequestBuilding.Get
import mesosphere.marathon.tasks.TaskTracker
import scala.concurrent.Future
import spray.client.pipelining._
import spray.http.HttpRequest
import spray.http.HttpResponse

object HealthCheckMessage extends Enumeration {
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

case class HealthCheckPayload(appDef: AppDefinition, healthCheckDefs: Seq[HealthCheckDefinition])
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

case class HealthActor(system: ActorSystem, taskTracker: TaskTracker)
    extends Actor with ActorLogging {

  import system.dispatcher // for ExecutionContext

  val pipeline: HttpRequest => Future[HttpResponse] = (
    addHeader("Accept", "application/json")
      ~> sendReceive)

  val healthChecks = HealthActorData.healthChecks

  private def insertHealthChecks(payload: HealthCheckPayload): Unit = {
    val existing = healthChecks(payload.appDef.id)
    for (healthCheckDef <- payload.healthCheckDefs) {
      if (!existing.map(_.healthCheckDef).contains(healthCheckDef)) {
        val cancellable =
          system.scheduler.schedule(healthCheckDef.initialDelay,
            healthCheckDef.interval,
            self,
            HealthCheckTick(payload))

        healthChecks(payload.appDef.id) = existing +
          HealthActorData.HealthCheckDescriptor(healthCheckDef, cancellable)
      }
    }
  }

  private def removeHealthChecks(appID: String, healthCheckDefs: Seq[HealthCheckDefinition]): Unit = {
    val existing = healthChecks(appID)
    for (healthCheckDef <- healthCheckDefs) {
      if (existing.map(_.healthCheckDef).contains(healthCheckDef)) {
        val descriptor = existing.find(_.healthCheckDef == healthCheckDef).get
        descriptor.cancellable.cancel()

        healthChecks(appID) = existing - descriptor
      }
    }
  }

  private def removeAllHealthChecks(): Unit = for {
    (appID, descriptors) <- healthChecks
  } removeHealthChecks(appID, descriptors.map(_.healthCheckDef).toSeq)

  private def doHealthCheck(tick: HealthCheckTick): Unit = {
    for (healthCheckDef <- tick.payload.healthCheckDefs) {
      assert(healthCheckDef.protocol == HealthCheckProtocol.HttpHealthCheck)

      val appDef = tick.payload.appDef
      val app = taskTracker.fetchApp(appDef.id)

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
          log.error(s"Health check failed for app.id: ${tick.payload.appDef.id}: path: ${f._1}. status: ${f._2}"))
        if (failures.isEmpty) log.info(s"Health check passed for app.id: ${tick.payload.appDef.id}")
      }
    }
  }

  def receive = {
    case packet: HealthMessagePacket => packet.message match {
      case HealthCheckMessage.RemoveAll => removeAllHealthChecks()
    }
    case packet: HealthMessagePacketPayload => packet.message match {
      case HealthCheckMessagePayload.Insert => insertHealthChecks(packet.payload)
      case HealthCheckMessagePayload.Remove =>
        removeHealthChecks(packet.payload.appDef.id, packet.payload.healthCheckDefs)
    }
    case tick: HealthCheckTick => doHealthCheck(tick)
  }
}
