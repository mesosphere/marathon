package mesosphere.marathon
package core.deployment

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition.UnreachableInactive
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.state.{AbsolutePathId, KillSelection, Timestamp}

case class ScalingProposition(toDecommission: Seq[Instance], toStart: Int)

object ScalingProposition extends StrictLogging {

  def propose(
    instances: Seq[Instance],
    toDecommission: Seq[Instance],
    meetConstraints: ((Seq[Instance], Int) => Seq[Instance]),
    scaleTo: Int,
    killSelection: KillSelection,
    runSpecId: AbsolutePathId): ScalingProposition = {

    val instancesGoalRunning: Map[Instance.Id, Instance] = instances
      .filter(_.state.goal == Goal.Running)
      .filter(_.state.condition != UnreachableInactive)
      .iterator.map(instance => instance.instanceId -> instance).toMap
    val toDecommissionMap: Map[Instance.Id, Instance] = toDecommission.iterator.map(instance => instance.instanceId -> instance).toMap

    val (sentencedAndRunningMap, notSentencedAndRunningMap) = instancesGoalRunning partition {
      case (instanceId, _) =>
        toDecommissionMap.contains(instanceId)
    }
    // overall number of tasks that need to be killed
    val decommissionCount = math.max(instancesGoalRunning.size - scaleTo, sentencedAndRunningMap.size)
    // tasks that should be killed to meet constraints – pass notSentenced & consider the sentenced 'already killed'
    val killToMeetConstraints = meetConstraints(
      notSentencedAndRunningMap.values.to(Seq),
      decommissionCount - sentencedAndRunningMap.size
    )

    // rest are tasks that are not sentenced and need not be killed to meet constraints
    val rest: Seq[Instance] = (notSentencedAndRunningMap -- killToMeetConstraints.map(_.instanceId)).values.to(Seq)

    val orderedDecommissionCandidates =
      Seq(sentencedAndRunningMap.values, killToMeetConstraints, rest.sortWith(sortByConditionAndDate(killSelection))).flatten

    val instancesToDecommission = orderedDecommissionCandidates.take(decommissionCount)
    val numberOfInstancesToStart = scaleTo - instancesGoalRunning.size + decommissionCount

    if (numberOfInstancesToStart > 0) {
      logger.info(s"Need to scale $runSpecId from ${instancesGoalRunning.size} up to $scaleTo instances")
    }
    if (instancesToDecommission.nonEmpty) {
      logger.info(s"Going to decommission instances '${instancesToDecommission.map(_.instanceId).mkString(",")}'." +
        s" of runspec $runSpecId. We have ${instancesGoalRunning.size} and we need $scaleTo instances.")
    }

    ScalingProposition(instancesToDecommission, numberOfInstancesToStart)
  }

  // TODO: this should evaluate a task's health as well
  /**
    * If we need to kill tasks, the order should be LOST - UNREACHABLE - UNHEALTHY - STAGING - (EVERYTHING ELSE)
    * If two task are staging kill with the latest staging timestamp.
    * If both are started kill the one according to the KillSelection.
    *
    * @param select Defines which instance to kill first if both have the same state.
    * @param a The instance that is compared to b
    * @param b The instance that is compared to a
    * @return true if a comes before b
    */
  def sortByConditionAndDate(select: KillSelection)(a: Instance, b: Instance): Boolean = {
    val weightA = weight(a.state.condition)
    val weightB = weight(b.state.condition)

    if (weightA < weightB) true
    else if (weightA > weightB) false
    else if (a.state.condition == Condition.Staging && b.state.condition == Condition.Staging) {
      // Both are staging.
      select(stagedAt(a), stagedAt(b))
    } else if (a.state.condition == Condition.Starting && b.state.condition == Condition.Starting) {
      select(a.state.since, b.state.since)
    } else {
      // Both are assumed to be started.
      // None is actually an error case :/
      (a.state.activeSince, b.state.activeSince) match {
        case (None, Some(_)) => true
        case (Some(_), None) => false
        case (Some(left), Some(right)) => select(left, right)
        case (None, None) => true
      }
    }

  }

  /** tasks with lower weight should be killed first */
  private val weight: Map[Condition, Int] = Map[Condition, Int](
    Condition.Unreachable -> 1,
    Condition.Staging -> 2,
    Condition.Starting -> 3,
    Condition.Running -> 4).withDefaultValue(5)

  private def stagedAt(instance: Instance): Timestamp = {
    val stagedTasks = instance.tasksMap.values.map(_.status.stagedAt)
    if (stagedTasks.nonEmpty) stagedTasks.max else Timestamp.now()
  }
}
