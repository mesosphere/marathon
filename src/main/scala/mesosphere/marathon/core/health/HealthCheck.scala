package mesosphere.marathon.core.health

import com.wix.accord._
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => MesosProtos }

import scala.concurrent.duration._

sealed trait HealthCheck extends MarathonState[Protos.HealthCheckDefinition, HealthCheck] {
  def gracePeriod: FiniteDuration
  def interval: FiniteDuration
  def timeout: FiniteDuration
  def maxConsecutiveFailures: Int
  def toProto: Protos.HealthCheckDefinition

  override def version: Timestamp = Timestamp.zero
  def mergeFromProto(bytes: Array[Byte]): HealthCheck =
    mergeFromProto(Protos.HealthCheckDefinition.parseFrom(bytes))
  def mergeFromProto(proto: Protos.HealthCheckDefinition): HealthCheck = HealthCheck.mergeFromProto(proto)

  protected def protoBuilder: Protos.HealthCheckDefinition.Builder =
    Protos.HealthCheckDefinition.newBuilder
      .setGracePeriodSeconds(this.gracePeriod.toSeconds.toInt)
      .setIntervalSeconds(this.interval.toSeconds.toInt)
      .setTimeoutSeconds(this.timeout.toSeconds.toInt)
      .setMaxConsecutiveFailures(this.maxConsecutiveFailures)
}

sealed trait HealthCheckWithPort { this: HealthCheck =>
  def portIndex: Option[Int]
  def port: Option[Int]

  def effectivePort(app: AppDefinition, task: Task): Option[Int]
}

object HealthCheckWithPort {
  import mesosphere.marathon.api.v2.Validation.isTrue

  implicit val Validator: Validator[HealthCheckWithPort] =
    isTrue("HealthCheck must specify either a port or a portIndex") { hc =>
      hc.portIndex.isDefined ^ hc.port.isDefined
    }
}

sealed trait MarathonHealthCheck extends HealthCheckWithPort { this: HealthCheck =>
  def portIndex: Option[Int]
  def port: Option[Int]

  def effectivePort(app: AppDefinition, task: Task): Option[Int] = {
    def portViaIndex: Option[Int] = portIndex.flatMap { portIndex =>
      app.portAssignments(task).flatMap(_.lift(portIndex)).map(_.effectivePort)
    }

    port.orElse(portViaIndex)
  }
}

sealed trait MesosHealthCheck { this: HealthCheck =>
  def gracePeriod: FiniteDuration
  def interval: FiniteDuration
  def timeout: FiniteDuration
  def maxConsecutiveFailures: Int

  def toMesos: MesosProtos.HealthCheck
}

case class HttpHealthCheck(
  gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,
  interval: FiniteDuration = HealthCheck.DefaultInterval,
  timeout: FiniteDuration = HealthCheck.DefaultTimeout,
  maxConsecutiveFailures: Int = HealthCheck.DefaultMaxConsecutiveFailures,
  portIndex: Option[Int] = HealthCheck.DefaultPortIndex,
  port: Option[Int] = HealthCheck.DefaultPort,
  path: Option[String] = HttpHealthCheck.DefaultPath,
  ignoreHttp1xx: Boolean = HttpHealthCheck.DefaultIgnoreHttp1xx,
  protocol: Protocol = HttpHealthCheck.DefaultProtocol)
    extends HealthCheck with MarathonHealthCheck {
  override def toProto: Protos.HealthCheckDefinition = {
    val builder = protoBuilder
      .setProtocol(protocol)
      .setIgnoreHttp1Xx(this.ignoreHttp1xx)

    path.foreach(builder.setPath)

    portIndex.foreach { p => builder.setPortIndex(p) }
    port.foreach { p => builder.setPort(p) }

    builder.build
  }
}

object HttpHealthCheck {
  val DefaultPath = None
  val DefaultIgnoreHttp1xx = false
  val DefaultProtocol = Protocol.HTTP

  def mergeFromProto(proto: Protos.HealthCheckDefinition): HttpHealthCheck =
    HttpHealthCheck(
      gracePeriod = proto.getGracePeriodSeconds.seconds,
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      maxConsecutiveFailures = proto.getMaxConsecutiveFailures,
      ignoreHttp1xx = proto.getIgnoreHttp1Xx,
      path = if (proto.hasPath) Some(proto.getPath) else None,
      portIndex =
      if (proto.hasPortIndex)
        Some(proto.getPortIndex)
      else if (!proto.hasPort && proto.getProtocol != Protocol.COMMAND)
        Some(0) // backward compatibility, this used to be the default value in marathon.proto
      else
        None,
      port = if (proto.hasPort) Some(proto.getPort) else None,
      protocol = proto.getProtocol
    )
}

case class TcpHealthCheck(
  gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,
  interval: FiniteDuration = HealthCheck.DefaultInterval,
  timeout: FiniteDuration = HealthCheck.DefaultTimeout,
  maxConsecutiveFailures: Int = HealthCheck.DefaultMaxConsecutiveFailures,
  portIndex: Option[Int] = HealthCheck.DefaultPortIndex,
  port: Option[Int] = HealthCheck.DefaultPort)
    extends HealthCheck with MarathonHealthCheck {
  override def toProto: Protos.HealthCheckDefinition = {
    val builder = protoBuilder.setProtocol(Protos.HealthCheckDefinition.Protocol.TCP)

    portIndex.foreach { p => builder.setPortIndex(p) }
    port.foreach { p => builder.setPort(p) }

    builder.build
  }
}

object TcpHealthCheck {
  def mergeFromProto(proto: Protos.HealthCheckDefinition): TcpHealthCheck =
    TcpHealthCheck(
      gracePeriod = proto.getGracePeriodSeconds.seconds,
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      maxConsecutiveFailures = proto.getMaxConsecutiveFailures,
      portIndex =
      if (proto.hasPortIndex)
        Some(proto.getPortIndex)
      else if (!proto.hasPort && proto.getProtocol != Protocol.COMMAND)
        Some(0) // backward compatibility, this used to be the default value in marathon.proto
      else
        None,
      port = if (proto.hasPort) Some(proto.getPort) else None
    )
}

case class CommandHealthCheck(
  gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,
  interval: FiniteDuration = HealthCheck.DefaultInterval,
  timeout: FiniteDuration = HealthCheck.DefaultTimeout,
  maxConsecutiveFailures: Int = HealthCheck.DefaultMaxConsecutiveFailures,
  command: Command)
    extends HealthCheck with MesosHealthCheck {
  override def toProto: Protos.HealthCheckDefinition = {
    protoBuilder
      .setProtocol(Protos.HealthCheckDefinition.Protocol.COMMAND)
      .setCommand(command.toProto)
      .build
  }

  def toMesos: MesosProtos.HealthCheck = {
    MesosProtos.HealthCheck.newBuilder
      .setIntervalSeconds(this.interval.toSeconds.toDouble)
      .setTimeoutSeconds(this.timeout.toSeconds.toDouble)
      .setConsecutiveFailures(this.maxConsecutiveFailures)
      .setGracePeriodSeconds(this.gracePeriod.toUnit(SECONDS))
      .setCommand(this.command.toProto)
      .build()
  }
}

object CommandHealthCheck {
  def mergeFromProto(proto: Protos.HealthCheckDefinition): CommandHealthCheck =
    CommandHealthCheck(
      gracePeriod = proto.getGracePeriodSeconds.seconds,
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      maxConsecutiveFailures = proto.getMaxConsecutiveFailures,
      command = Command("").mergeFromProto(proto.getCommand)
    )
}

object HealthCheck {
  val DefaultProtocol = Protocol.HTTP
  // Docker images can take a long time to download, so default to a fairly long wait.
  val DefaultGracePeriod = 5.minutes
  val DefaultInterval = 1.minute
  val DefaultTimeout = 20.seconds
  val DefaultMaxConsecutiveFailures = 3
  val DefaultPortIndex = None
  val DefaultPort = None

  implicit val Validator: Validator[HealthCheck] = new Validator[HealthCheck] {
    override def apply(hc: HealthCheck): Result = {
      hc match {
        case h: HealthCheckWithPort => HealthCheckWithPort.Validator(h)
        case _ => Success
      }
    }
  }

  def mergeFromProto(proto: Protos.HealthCheckDefinition): HealthCheck = {
    proto.getProtocol match {
      case Protocol.COMMAND => CommandHealthCheck.mergeFromProto(proto)
      case Protocol.TCP => TcpHealthCheck.mergeFromProto(proto)
      case Protocol.HTTP | Protocol.HTTPS => HttpHealthCheck.mergeFromProto(proto)
    }
  }
}
