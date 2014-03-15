package mesosphere.marathon.api.v1

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import mesosphere.marathon.api.FieldConstraints._
import mesosphere.marathon.Protos
import scala.concurrent.duration._
import scala.collection.JavaConversions._
import com.fasterxml.jackson.core.`type`.TypeReference
import mesosphere.marathon.state.{MarathonState, Timestamped}

object HealthCheckProtocol extends Enumeration {
  val HttpHealthCheck = Value
  // TODO: Add TcpHealthCheck
}

class HealthCheckProtocolType extends TypeReference[HealthCheckProtocol.type]

@JsonIgnoreProperties(ignoreUnknown = true)
case class HealthCheckDefinition(
  @FieldNotEmpty
  path: String = "",

  @FieldNotEmpty
  @FieldJsonScalaEnumeration(classOf[HealthCheckProtocolType])
  protocol: HealthCheckProtocol.Value = HealthCheckProtocol.HttpHealthCheck,

  @FieldNotEmpty
  acceptableResponses: List[Int] = List(),

  @FieldNotEmpty
  portIndex: Integer = -1,

  initialDelay: Duration = 10.seconds,
  interval: Duration = 60.seconds
) extends MarathonState[Protos.HealthCheckDefinition, HealthCheckDefinition] {

  // the default constructor exists solely for interop with automatic
  // (de)serializers
  def this() = this(path = "", protocol = HealthCheckProtocol.HttpHealthCheck,
    acceptableResponses = List(), portIndex = -1)

  def toProto: Protos.HealthCheckDefinition = {
    val builder = Protos.HealthCheckDefinition.newBuilder
      .setPath(this.path)
      .setProtocol(this.protocol.toString)
      .setPortIndex(this.portIndex)
      .setInitialDelaySeconds(this.initialDelay.toSeconds.toInt)
      .setIntervalSeconds(this.interval.toSeconds.toInt)

    builder.addAllAcceptableResponses(acceptableResponses.map(_.asInstanceOf[Integer]))
    builder.build
  }

  def mergeFromProto(proto: Protos.HealthCheckDefinition): HealthCheckDefinition = {
    HealthCheckDefinition(
      path = proto.getPath,
      protocol = HealthCheckProtocol.withName(proto.getProtocol),
      acceptableResponses = proto.getAcceptableResponsesList.toList.map(_.asInstanceOf[Int]),
      portIndex = proto.getPortIndex,
      initialDelay = proto.getInitialDelaySeconds.seconds,
      interval = proto.getIntervalSeconds.seconds
    )
  }

  def mergeFromProto(bytes: Array[Byte]): HealthCheckDefinition =
    mergeFromProto(Protos.HealthCheckDefinition.parseFrom(bytes))
}
