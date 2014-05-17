package mesosphere.marathon.health

import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.state.MarathonState
import mesosphere.marathon.api.validation.FieldConstraints.FieldJsonInclude

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude.Include

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit.SECONDS

import java.lang.{Integer => JInt}

@JsonIgnoreProperties(ignoreUnknown = true)
case class HealthCheck(

  @FieldJsonInclude(Include.NON_NULL)
  @FieldJsonDeserialize(contentAs = classOf[java.lang.String])
  path: Option[String] = HealthCheck.DefaultPath,

  @FieldNotEmpty
  protocol: Protocol = HealthCheck.DefaultProtocol,

  @FieldNotEmpty
  portIndex: JInt = HealthCheck.DefaultPortIndex,

  @FieldJsonProperty("gracePeriodSeconds")
  gracePeriod: FiniteDuration = HealthCheck.DefaultGracePeriod,

  @FieldJsonProperty("intervalSeconds")
  interval: FiniteDuration = HealthCheck.DefaultInterval,

  @FieldJsonProperty("timeoutSeconds")
  timeout: FiniteDuration = HealthCheck.DefaultTimeout,

  @FieldNotEmpty
  maxConsecutiveFailures: JInt = HealthCheck.DefaultMaxConsecutiveFailures

) extends MarathonState[Protos.HealthCheckDefinition, HealthCheck] {

  def toProto: Protos.HealthCheckDefinition = {
    val builder = Protos.HealthCheckDefinition.newBuilder
      .setProtocol(this.protocol)
      .setPortIndex(this.portIndex)
      .setGracePeriodSeconds(this.gracePeriod.toSeconds.toInt)
      .setIntervalSeconds(this.interval.toSeconds.toInt)
      .setTimeoutSeconds(this.timeout.toSeconds.toInt)
      .setMaxConsecutiveFailures(this.maxConsecutiveFailures)

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

}

object HealthCheck {

  val DefaultPath                   = Some("/")
  val DefaultProtocol               = Protocol.HTTP
  val DefaultPortIndex              = 0
  val DefaultGracePeriod            = FiniteDuration(15, SECONDS)
  val DefaultInterval               = FiniteDuration(10, SECONDS)
  val DefaultTimeout                = FiniteDuration(20, SECONDS)
  val DefaultMaxConsecutiveFailures = 3
}
