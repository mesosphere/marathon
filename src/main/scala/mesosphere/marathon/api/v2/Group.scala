package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v1.AppDefinition
import com.fasterxml.jackson.databind.annotation.{JsonSerialize, JsonDeserialize}
import mesosphere.marathon.api.v2.json.MarathonModule.{StepSerializer, StepDeserializer}
import mesosphere.marathon.state.{MarathonState, Timestamp}
import mesosphere.marathon.Protos._
import scala.collection.JavaConversions._

@JsonDeserialize(using = classOf[StepDeserializer], as = classOf[Step])
@JsonSerialize(using = classOf[StepSerializer])
sealed trait Step {
  def count(app: AppDefinition): Int
}

final case class AbsoluteStep(_count: Int) extends Step {
  def count(app: AppDefinition): Int = _count
}

final case class RelativeStep(factor: Double) extends Step {
  def count(app: AppDefinition) = (app.instances * factor).toInt
}

final case class ScalingStrategy(
  steps: Seq[Step],
  watchPeriod: Int) {

  def toProto : ScalingStrategyDefinition = {
    val stepDefinitions = steps.map {
      case AbsoluteStep(count) => StepDefinition.newBuilder().setCount(count).setKind(StepDefinition.StepKind.Absolute).build()
      case RelativeStep(count) => StepDefinition.newBuilder().setCount(count).setKind(StepDefinition.StepKind.Relative).build()
    }
    ScalingStrategyDefinition.newBuilder()
      .setWatchPeriod(watchPeriod)
      .addAllSteps(stepDefinitions)
      .build()
  }
}

final case class Group (
  id: String,
  scalingStrategy: ScalingStrategy,
    apps: Seq[AppDefinition],
  version: Timestamp = Timestamp.now()
) extends MarathonState[GroupDefinition, Group] {

  override def mergeFromProto(msg: GroupDefinition): Group = {
    def scalingStrategyFromProto(msg: ScalingStrategyDefinition): ScalingStrategy = {
      val steps = msg.getStepsList.map { step =>
        step.getKind match {
          case StepDefinition.StepKind.Absolute => AbsoluteStep(step.getCount.toInt)
          case StepDefinition.StepKind.Relative => RelativeStep(step.getCount)
        }
      }
      ScalingStrategy(steps, msg.getWatchPeriod)
    }
    Group(
      id = msg.getId,
      scalingStrategy = scalingStrategyFromProto(msg.getScalingStrategy),
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
  def empty() : Group = Group("", ScalingStrategy(Seq.empty, 0), Seq.empty)
}
