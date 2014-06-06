package mesosphere.marathon.state

import mesosphere.marathon.Protos.{ GroupDefinition, ScalingStrategyDefinition }
import mesosphere.marathon.api.v1.AppDefinition
import scala.collection.JavaConversions._

case class ScalingStrategy(
    minimumHealthCapacity: Double,
    maximumRunningFactor: Option[Double]) {

  def toProto: ScalingStrategyDefinition = {
    val strategy = ScalingStrategyDefinition.newBuilder()
      .setMinimumHealthCapacity(minimumHealthCapacity)
    maximumRunningFactor.foreach(strategy.setMaximumRunningFactor)
    strategy.build()
  }
}

case class Group(
    id: GroupId,
    scalingStrategy: ScalingStrategy,
    apps: Seq[AppDefinition] = Seq.empty,
    groups: Seq[Group] = Seq.empty,
    version: Timestamp = Timestamp.now()) extends MarathonState[GroupDefinition, Group] {

  override def mergeFromProto(msg: GroupDefinition): Group = Group.fromProto(msg)
  override def mergeFromProto(bytes: Array[Byte]): Group = Group.fromProto(GroupDefinition.parseFrom(bytes))

  override def toProto: GroupDefinition = {
    GroupDefinition.newBuilder
      .setId(id)
      .setScalingStrategy(scalingStrategy.toProto)
      .setVersion(version.toString)
      .addAllApps(apps.map(_.toProto))
      .addAllGroups(groups.map(_.toProto))
      .build()
  }

  def findGroup(fn: Group => Boolean): Option[Group] = {
    def in(groups: List[Group]): Option[Group] = groups match {
      case head :: rest => if (fn(head)) Some(head) else in(rest).orElse(in(head.groups.toList))
      case Nil          => None
    }
    if (fn(this)) Some(this) else in(groups.toList)
  }

  def transitiveApps: Seq[AppDefinition] = this.apps ++ groups.flatMap(_.transitiveApps)
}

object Group {
  def empty(): Group = Group(GroupId(Nil), ScalingStrategy(0, None))

  def fromProto(msg: GroupDefinition): Group = {
    val scalingStrategy = msg.getScalingStrategy
    val maximumRunningFactor = {
      if (scalingStrategy.hasMaximumRunningFactor) Some(msg.getScalingStrategy.getMaximumRunningFactor)
      else None
    }
    Group(
      id = msg.getId,
      scalingStrategy = ScalingStrategy(
        scalingStrategy.getMinimumHealthCapacity,
        maximumRunningFactor
      ),
      apps = msg.getAppsList.map(AppDefinition.fromProto),
      groups = msg.getGroupsList.map(fromProto),
      version = Timestamp(msg.getVersion)
    )
  }
}

