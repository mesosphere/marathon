package mesosphere.marathon.api.v1

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import mesosphere.marathon.api.FieldConstraints._
import mesosphere.marathon.Protos
import scala.concurrent.duration._
import scala.collection.JavaConversions._

object HealthCheckProtocol extends Enumeration {
  val HttpHealthCheck = Value
  // TODO: Add TcpHealthCheck
}

@JsonIgnoreProperties(ignoreUnknown = true)
case class HealthCheckDefinition(
    @FieldNotEmpty
    path: String,

    @FieldNotEmpty
    protocol: HealthCheckProtocol.Value,

    @FieldNotEmpty
    acceptableResponses: List[Int],

    @FieldNotEmpty
    portIndex: Integer,

    initialDelay: Duration = 10.seconds,
    interval: Duration = 60.seconds
  ) {
  def toProto: Protos.HealthCheckDefinition = {
    val builder = Protos.HealthCheckDefinition.newBuilder
      .setPath(this.path)
      .setProtocol(this.protocol.toString)
      .setPortIndex(this.portIndex)
      .setInitialDelaySeconds(this.initialDelay.toSeconds.toInt)
      .setIntervalSeconds(this.interval.toSeconds.toInt)

    acceptableResponses.zipWithIndex.foreach(tup =>
      builder.setAcceptableResponses(tup._2, tup._1))

    builder.build
  }
}

object HealthCheckDefinition {
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
}
