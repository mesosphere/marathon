package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.{MarathonState, Timestamp}
import mesosphere.marathon.Protos._
import scala.collection.JavaConversions._
import mesosphere.marathon.api.validation.FieldConstraints.{FieldPattern, FieldNotEmpty}
import javax.validation.Valid

case class ScalingStrategy(

  minimumHealthCapacity: java.lang.Double

) {

  def toProto : ScalingStrategyDefinition = {
    ScalingStrategyDefinition.newBuilder().setMinimumHealthCapacity(minimumHealthCapacity).build()
  }
}

case class Group (

  @FieldNotEmpty
  @FieldPattern(regexp = "^[A-Za-z0-9_.-]+$")
  id: String,

  scalingStrategy: ScalingStrategy,

  apps: Seq[AppDefinition],

  version: Timestamp = Timestamp.now()
) extends MarathonState[GroupDefinition, Group] {

  override def mergeFromProto(msg: GroupDefinition): Group = {
    Group(
      id = msg.getId,
      scalingStrategy = ScalingStrategy(msg.getScalingStrategy.getMinimumHealthCapacity),
      apps = msg.getAppsList.map(AppDefinition.fromProto),
      version = Timestamp(msg.getVersion)
    )
  }

  override def mergeFromProto(bytes: Array[Byte]): Group = {
    val proto = GroupDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }

  override def toProto: GroupDefinition = {
    GroupDefinition.newBuilder
      .setId(id)
      .setScalingStrategy(scalingStrategy.toProto)
      .setVersion(version.toString)
      .addAllApps(apps.map(_.toProto))
      .build()
  }
}

object Group {
  def empty() : Group = Group("", ScalingStrategy(0), Seq.empty)
}
