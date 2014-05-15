package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.{MarathonState, Timestamp}
import mesosphere.marathon.Protos._
import scala.collection.JavaConversions._

case class ScalingStrategy( minimumHealthCapacity: Double ) {
  def toProto : ScalingStrategyDefinition = {
    ScalingStrategyDefinition.newBuilder().setMinimumHealthCapacity(minimumHealthCapacity).build()
  }
}

object ScalingStrategy {
  def fromProto(msg:ScalingStrategyDefinition) : ScalingStrategy = ScalingStrategy(msg.getMinimumHealthCapacity)
}

final case class Group (
  id: String,
  scalingStrategy: ScalingStrategy,
    apps: Seq[AppDefinition],
  version: Timestamp = Timestamp.now()
) extends MarathonState[GroupDefinition, Group] {

  override def mergeFromProto(msg: GroupDefinition): Group = {
    Group(
      id = msg.getId,
      scalingStrategy = ScalingStrategy.fromProto(msg.getScalingStrategy),
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
