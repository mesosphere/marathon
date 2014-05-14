package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v1.AppDefinition
import com.fasterxml.jackson.databind.annotation.{JsonSerialize, JsonDeserialize}
import mesosphere.marathon.api.v2.json.MarathonModule.{StepSerializer, StepDeserializer}
import mesosphere.marathon.state.{MarathonState, Timestamp}
import mesosphere.marathon.Protos._
import scala.collection.JavaConversions._
import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonTypeInfo, JsonSubTypes}
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo._
import mesosphere.marathon.Protos.ScalingStrategyDefinition.StrategyKind._
import mesosphere.marathon.Protos.StepDefinition.StepKind._

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

@JsonTypeInfo(use=Id.NAME, include=As.PROPERTY, property="type")
@JsonSubTypes(Array(new Type(value=classOf[RollingStrategy], name="rolling"), new Type(value=classOf[CanaryStrategy], name="canary")))
@JsonDeserialize(as=classOf[RollingStrategy]) //TODO: allow other strategies as well
@JsonIgnoreProperties(ignoreUnknown = true)
trait ScalingStrategy {
  def toProto : ScalingStrategyDefinition
}

object ScalingStrategy {
  def fromProto(msg:ScalingStrategyDefinition) : ScalingStrategy = {
    def canary(msg:ScalingStrategyDefinition) : CanaryStrategy = {
      val steps = msg.getStepsList.map { step =>
        step.getKind match {
          case Absolute => AbsoluteStep(step.getCount.toInt)
          case Relative => RelativeStep(step.getCount)
        }
      }
      CanaryStrategy(steps, msg.getWatchPeriod)
    }
    def rolling(msg:ScalingStrategyDefinition) : RollingStrategy = RollingStrategy(msg.getMinimumHealthCapacity)
    msg.getKind match {
      case Rolling => rolling(msg)
      case Canary => canary(msg)
    }
  }
}

case class RollingStrategy( minimumHealthCapacity: Double ) extends ScalingStrategy {
  def toProto : ScalingStrategyDefinition = {
    ScalingStrategyDefinition.newBuilder()
      .setKind(Rolling)
      .setMinimumHealthCapacity(minimumHealthCapacity)
      .build()
  }
}

case class CanaryStrategy( steps: Seq[Step], watchPeriod: Int) extends ScalingStrategy {
  def toProto : ScalingStrategyDefinition = {
    val stepDefinitions = steps.map {
      case AbsoluteStep(count) => StepDefinition.newBuilder().setCount(count).setKind(Absolute).build()
      case RelativeStep(count) => StepDefinition.newBuilder().setCount(count).setKind(Relative).build()
    }
    ScalingStrategyDefinition.newBuilder()
      .setKind(Canary)
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
  def empty() : Group = Group("", CanaryStrategy(Seq.empty, 0), Seq.empty)
}
