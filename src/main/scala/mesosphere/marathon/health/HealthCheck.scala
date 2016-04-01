package mesosphere.marathon.health

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ Command, MarathonState, Timestamp }
import org.apache.mesos.{ Protos => MesosProtos }

import scala.concurrent.duration._

case class HealthCheck(

  path: Option[String] = HealthCheck.DefaultPath,

  protocol: Protocol = HealthCheck.DefaultProtocol,

  portIndex: Option[Int] = HealthCheck.DefaultPortIndex,

  command: Option[Command] = HealthCheck.DefaultCommand,

  gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,

  interval: FiniteDuration = HealthCheck.DefaultInterval,

  timeout: FiniteDuration = HealthCheck.DefaultTimeout,

  maxConsecutiveFailures: Int = HealthCheck.DefaultMaxConsecutiveFailures,

  ignoreHttp1xx: Boolean = HealthCheck.DefaultIgnoreHttp1xx,

  port: Option[Int] = HealthCheck.DefaultPort)
    extends MarathonState[Protos.HealthCheckDefinition, HealthCheck] {

  def toProto: Protos.HealthCheckDefinition = {
    val builder = Protos.HealthCheckDefinition.newBuilder
      .setProtocol(this.protocol)
      .setGracePeriodSeconds(this.gracePeriod.toSeconds.toInt)
      .setIntervalSeconds(this.interval.toSeconds.toInt)
      .setTimeoutSeconds(this.timeout.toSeconds.toInt)
      .setMaxConsecutiveFailures(this.maxConsecutiveFailures)
      .setIgnoreHttp1Xx(this.ignoreHttp1xx)

    command foreach { c => builder.setCommand(c.toProto) }

    path foreach builder.setPath

    portIndex foreach { p => builder.setPortIndex(p.toInt) }
    port foreach { p => builder.setPort(p.toInt) }

    builder.build
  }

  def mergeFromProto(proto: Protos.HealthCheckDefinition): HealthCheck =
    HealthCheck(
      path =
        if (proto.hasPath) Some(proto.getPath) else None,
      protocol = proto.getProtocol,
      portIndex =
        if (proto.hasPortIndex)
          Some(proto.getPortIndex)
        else if (!proto.hasPort && proto.getProtocol != Protocol.COMMAND)
          Some(0) // backward compatibility, this used to be the default value in marathon.proto
        else
          None,
      command =
        if (proto.hasCommand) Some(Command("").mergeFromProto(proto.getCommand))
        else None,
      gracePeriod = proto.getGracePeriodSeconds.seconds,
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      maxConsecutiveFailures = proto.getMaxConsecutiveFailures,
      ignoreHttp1xx = proto.getIgnoreHttp1Xx,
      port = if (proto.hasPort) Some(proto.getPort) else None
    )

  def mergeFromProto(bytes: Array[Byte]): HealthCheck =
    mergeFromProto(Protos.HealthCheckDefinition.parseFrom(bytes))

  // Mesos supports COMMAND health checks, others to be added in the future
  def toMesos: MesosProtos.HealthCheck = {
    val builder = this.protocol match {
      case Protocol.COMMAND =>
        assert(
          command.isDefined,
          "A command is required when using the COMMAND health check protocol."
        )
        MesosProtos.HealthCheck.newBuilder
          .setCommand(this.command.get.toProto)

      case Protocol.HTTP =>
        throw new UnsupportedOperationException(
          s"Mesos does not support health checks of type [$protocol]")

      case _ =>
        throw new UnsupportedOperationException(
          s"Mesos does not support health checks of type [$protocol]")
    }

    builder.setDelaySeconds(0)
      .setIntervalSeconds(this.interval.toSeconds.toDouble)
      .setTimeoutSeconds(this.timeout.toSeconds.toDouble)
      .setConsecutiveFailures(this.maxConsecutiveFailures)
      .setGracePeriodSeconds(this.gracePeriod.toUnit(SECONDS))
      .build
  }

  def hostPort(launched: Task.Launched): Option[Int] = {
    def portViaIndex: Option[Int] = portIndex.flatMap(launched.hostPorts.lift(_))
    port.orElse(portViaIndex)
  }

  override def version: Timestamp = Timestamp.zero
}

object HealthCheck {
  val DefaultPath = None
  val DefaultProtocol = Protocol.HTTP
  val DefaultPortIndex = None
  val DefaultCommand = None
  // Dockers can take a long time to download, so default to a fairly long wait.
  val DefaultGracePeriod = 5.minutes
  val DefaultInterval = 1.minute
  val DefaultTimeout = 20.seconds
  val DefaultMaxConsecutiveFailures = 3
  val DefaultIgnoreHttp1xx = false
  val DefaultPort = None

  implicit val healthCheck = validator[HealthCheck] { hc =>
    (hc.portIndex.nonEmpty is true) or (hc.port.nonEmpty is true)
    hc is validProtocol
  }

  //scalastyle:off
  private def validProtocol: Validator[HealthCheck] = {
    new Validator[HealthCheck] {
      override def apply(hc: HealthCheck): Result = {
        def eitherPortIndexOrPort: Boolean = hc.portIndex.isDefined ^ hc.port.isDefined
        val hasCommand = hc.command.isDefined
        val hasPath = hc.path.isDefined
        if (hc.protocol match {
          case Protocol.COMMAND => hasCommand && !hasPath && hc.port.isEmpty
          case Protocol.HTTP    => !hasCommand && eitherPortIndexOrPort
          case Protocol.TCP     => !hasCommand && !hasPath && eitherPortIndexOrPort
          case _                => true
        }) Success else Failure(Set(RuleViolation(hc, s"HealthCheck is having parameters violation ${hc.protocol} protocol.", None)))
      }
    }
  }
  //scalastyle:on
}
