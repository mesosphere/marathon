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

  @FieldJsonProperty("initialDelaySeconds")
  initialDelay: FiniteDuration = HealthCheck.DefaultInitialDelay,

  @FieldJsonProperty("intervalSeconds")
  interval: FiniteDuration = HealthCheck.DefaultInterval,

  @FieldJsonProperty("timeoutSeconds")
  timeout: FiniteDuration = HealthCheck.DefaultTimeout

) extends MarathonState[Protos.HealthCheckDefinition, HealthCheck] {

  def toProto: Protos.HealthCheckDefinition = {
    val builder = Protos.HealthCheckDefinition.newBuilder
      .setProtocol(this.protocol)
      .setPortIndex(this.portIndex)
      .setInitialDelaySeconds(this.initialDelay.toSeconds.toInt)
      .setIntervalSeconds(this.interval.toSeconds.toInt)
      .setTimeoutSeconds(this.timeout.toSeconds.toInt)

    path foreach builder.setPath
    builder.build
  }

  def mergeFromProto(proto: Protos.HealthCheckDefinition): HealthCheck =
    HealthCheck(
      path = Option(proto.getPath),
      protocol = proto.getProtocol,
      portIndex = proto.getPortIndex,
      initialDelay = FiniteDuration(proto.getInitialDelaySeconds, SECONDS),
      timeout = FiniteDuration(proto.getTimeoutSeconds, SECONDS),
      interval = FiniteDuration(proto.getIntervalSeconds, SECONDS)
    )

  def mergeFromProto(bytes: Array[Byte]): HealthCheck =
    mergeFromProto(Protos.HealthCheckDefinition.parseFrom(bytes))

}

object HealthCheck {

  val DefaultPath                 = Some("/")
  val DefaultProtocol             = Protocol.HTTP
  val DefaultAcceptableResponses  = None
  val DefaultPortIndex            = 0
  val DefaultInitialDelay         = FiniteDuration(15, SECONDS)
  val DefaultInterval             = FiniteDuration(60, SECONDS)
  val DefaultTimeout              = FiniteDuration(15, SECONDS)
}
