package mesosphere.marathon.health

import akka.actor._
import mesosphere.marathon.api.v1.HealthCheckDefinition
import scala.collection.mutable
import mesosphere.marathon.api.v1.Implicits.DurationToFiniteDuration
import mesosphere.marathon.MarathonSchedulerService

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

case class HealthActor(system: ActorSystem, service: MarathonSchedulerService)
    extends Actor with ActorLogging {

  case class HealthCheckDescriptor(healthCheckDef: HealthCheckDefinition,
      cancellable: Cancellable)

  val healthChecks = mutable.Map[String, Set[HealthCheckDescriptor]]().withDefaultValue(
    Set.empty[HealthCheckDescriptor])

  private def insertHealthCheck(payload: HealthCheckPayload): Unit = {
    val existing = healthChecks(payload.appID)
    if (!existing.map(_.healthCheckDef).contains(payload.healthCheckDef)) {
      import system.dispatcher // for ExecutionContext

      val cancellable =
        system.scheduler.schedule(payload.healthCheckDef.initialDelay,
          payload.healthCheckDef.interval,
          self,
          HealthCheckTick(payload))

      healthChecks(payload.appID) = existing +
        HealthCheckDescriptor(payload.healthCheckDef, cancellable)
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
    healthCheck <- app.healthCheck
  } insertHealthCheck(HealthCheckPayload(app.id, healthCheck))

  def receive = {
    case packet: HealthMessagePacket => packet.message match {
      case HealthCheckMessage.AddAll => addAllHealthChecks()
      case HealthCheckMessage.RemoveAll => removeAllHealthChecks()
    }
    case packet: HealthMessagePacketPayload => packet.message match {
      case HealthCheckMessagePayload.Insert => insertHealthCheck(packet.payload)
      case HealthCheckMessagePayload.Remove =>
        removeHealthCheck(packet.payload.appID, packet.payload.healthCheckDef)
    }
  }
}
