package mesosphere.marathon.health

import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.state.MarathonState
import mesosphere.marathon.api.validation.FieldConstraints.FieldJsonInclude

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude.Include

import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConverters._
import java.util.concurrent.TimeUnit.SECONDS

import java.lang.{Integer => JInt, Double => JDouble}

@JsonIgnoreProperties(ignoreUnknown = true)
case class HealthCheck(

  @FieldJsonInclude(Include.NON_NULL)
  @FieldJsonDeserialize(contentAs = classOf[java.lang.String])
  val path: Option[String] = HealthCheck.DEFAULT_PATH,

  @FieldNotEmpty
  val protocol: Protocol = HealthCheck.DEFAULT_PROTOCOL,

  @FieldNotEmpty
  val portIndex: JInt = HealthCheck.DEFAULT_PORT_INDEX,

  @FieldJsonProperty("initialDelaySeconds")
  val initialDelay: FiniteDuration = HealthCheck.DEFAULT_INITIAL_DELAY,

  @FieldJsonProperty("intervalSeconds")
  val interval: FiniteDuration = HealthCheck.DEFAULT_INTERVAL,

  @FieldJsonProperty("timeoutSeconds")
  val timeout: FiniteDuration = HealthCheck.DEFAULT_TIMEOUT

) extends MarathonState[Protos.HealthCheckDefinition, HealthCheck] {

  def toProto: Protos.HealthCheckDefinition = {
    val builder = Protos.HealthCheckDefinition.newBuilder
      .setProtocol(this.protocol)
      .setPortIndex(this.portIndex)
      .setInitialDelaySeconds(this.initialDelay.toSeconds.toInt)
      .setIntervalSeconds(this.interval.toSeconds.toInt)
      .setTimeoutSeconds(this.timeout.toSeconds.toInt)

    path foreach { builder.setPath(_) }
    builder.build
  }

  def mergeFromProto(proto: Protos.HealthCheckDefinition): HealthCheck =
    new HealthCheck(
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

  val DEFAULT_PATH                 = Some("/")
  val DEFAULT_PROTOCOL             = Protocol.HTTP
  val DEFAULT_ACCEPTABLE_RESPONSES = None
  val DEFAULT_PORT_INDEX           = 0
  val DEFAULT_INITIAL_DELAY        = FiniteDuration(15, SECONDS)
  val DEFAULT_INTERVAL             = FiniteDuration(60, SECONDS)
  val DEFAULT_TIMEOUT              = FiniteDuration(15, SECONDS)
}