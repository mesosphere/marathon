package mesosphere.marathon.health

import java.lang.{ Integer => JInt }

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.api.validation.ValidHealthCheck
import mesosphere.marathon.state.{ Command, MarathonState }
import org.apache.mesos.{ Protos => MesosProtos }

import scala.concurrent.duration._

@ValidHealthCheck
@JsonIgnoreProperties(ignoreUnknown = true)
case class HealthCheck(

  @FieldJsonDeserialize(contentAs = classOf[java.lang.String]) path: Option[String] = HealthCheck.DefaultPath,

  protocol: Protocol = HealthCheck.DefaultProtocol,

  portIndex: JInt = HealthCheck.DefaultPortIndex,

  command: Option[Command] = HealthCheck.DefaultCommand,

  @FieldJsonProperty("gracePeriodSeconds") gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,

  @FieldJsonProperty("intervalSeconds") interval: FiniteDuration = HealthCheck.DefaultInterval,

  @FieldJsonProperty("timeoutSeconds") timeout: FiniteDuration = HealthCheck.DefaultTimeout,

  maxConsecutiveFailures: JInt = HealthCheck.DefaultMaxConsecutiveFailures)
    extends MarathonState[Protos.HealthCheckDefinition, HealthCheck] {

  def toProto: Protos.HealthCheckDefinition = {
    val builder = Protos.HealthCheckDefinition.newBuilder
      .setProtocol(this.protocol)
      .setPortIndex(this.portIndex)
      .setGracePeriodSeconds(this.gracePeriod.toSeconds.toInt)
      .setIntervalSeconds(this.interval.toSeconds.toInt)
      .setTimeoutSeconds(this.timeout.toSeconds.toInt)
      .setMaxConsecutiveFailures(this.maxConsecutiveFailures)

    command foreach { c => builder.setCommand(c.toProto) }

    path foreach builder.setPath

    builder.build
  }

  def mergeFromProto(proto: Protos.HealthCheckDefinition): HealthCheck =
    HealthCheck(
      path =
        if (proto.hasPath) Some(proto.getPath) else None,
      protocol = proto.getProtocol,
      portIndex = proto.getPortIndex,
      command =
        if (proto.hasCommand) Some(Command("").mergeFromProto(proto.getCommand))
        else None,
      gracePeriod = proto.getGracePeriodSeconds.seconds,
      timeout = proto.getTimeoutSeconds.seconds,
      interval = proto.getIntervalSeconds.seconds,
      maxConsecutiveFailures = proto.getMaxConsecutiveFailures
    )

  def mergeFromProto(bytes: Array[Byte]): HealthCheck =
    mergeFromProto(Protos.HealthCheckDefinition.parseFrom(bytes))

  // Mesos supports COMMAND health checks, others to be added in the future
  def toMesos(ports: Seq[Int]): MesosProtos.HealthCheck = {
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
      // TODO: enable this logic once Mesos supports HTTP checks
      //
      // val httpBuilder = MesosProtos.HealthCheck.HTTP.newBuilder
      //   .setPort(ports(portIndex))
      //
      // path foreach httpBuilder.setPath
      //
      // MesosProtos.HealthCheck.newBuilder
      //   .setHttp(httpBuilder)

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

}

object HealthCheck {
  val DefaultPath = Some("/")
  val DefaultProtocol = Protocol.HTTP
  val DefaultPortIndex = 0
  val DefaultCommand = None
  val DefaultGracePeriod = 15.seconds
  val DefaultInterval = 10.seconds
  val DefaultTimeout = 20.seconds
  val DefaultMaxConsecutiveFailures = 3
}
