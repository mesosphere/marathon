package mesosphere.marathon.health

import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.api.v2.Command
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.state.MarathonState
import mesosphere.marathon.api.validation.ValidHealthCheck

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude.Include
import org.apache.mesos.{ Protos => MesosProtos }

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit.SECONDS

import java.lang.{ Integer => JInt }

@ValidHealthCheck
@JsonIgnoreProperties(ignoreUnknown = true)
case class HealthCheck(

  @FieldJsonInclude(Include.NON_NULL)@FieldJsonDeserialize(contentAs = classOf[java.lang.String]) path: Option[String] = HealthCheck.DefaultPath,

  protocol: Protocol = HealthCheck.DefaultProtocol,

  portIndex: JInt = HealthCheck.DefaultPortIndex,

  @FieldJsonInclude(Include.NON_NULL)@FieldJsonDeserialize(contentAs = classOf[Command]) command: Option[Command] = HealthCheck.DefaultCommand,

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
      path = Option(proto.getPath),
      protocol = proto.getProtocol,
      portIndex = proto.getPortIndex,
      gracePeriod = FiniteDuration(proto.getGracePeriodSeconds, SECONDS),
      timeout = FiniteDuration(proto.getTimeoutSeconds, SECONDS),
      interval = FiniteDuration(proto.getIntervalSeconds, SECONDS),
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
      .setIntervalSeconds(this.interval.toSeconds.toInt)
      .setTimeoutSeconds(this.timeout.toSeconds.toInt)
      .setConsecutiveFailures(this.maxConsecutiveFailures)
      .setGracePeriodSeconds(this.gracePeriod.toSeconds.toInt)
      .build
  }

}

object HealthCheck {
  val DefaultPath = Some("/")
  val DefaultProtocol = Protocol.HTTP
  val DefaultPortIndex = 0
  val DefaultCommand = None
  val DefaultGracePeriod = FiniteDuration(15, SECONDS)
  val DefaultInterval = FiniteDuration(10, SECONDS)
  val DefaultTimeout = FiniteDuration(20, SECONDS)
  val DefaultMaxConsecutiveFailures = 3
}
