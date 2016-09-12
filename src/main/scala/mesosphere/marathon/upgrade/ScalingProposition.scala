package mesosphere.marathon.upgrade

import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.state.Timestamp

case class ScalingProposition(tasksToKill: Option[Seq[Instance]], tasksToStart: Option[Int])

object ScalingProposition {
  def propose(
    runningTasks: Iterable[Instance],
    toKill: Option[Iterable[Instance]],
    meetConstraints: ((Iterable[Instance], Int) => Iterable[Instance]),
    scaleTo: Int): ScalingProposition = {

    // TODO: tasks in state KILLING shouldn't be killed and should decrease the amount to kill
    val runningTaskMap = Instance.instancesById(runningTasks)
    val toKillMap = Instance.instancesById(toKill.getOrElse(Set.empty))

    val (sentencedAndRunningMap, notSentencedAndRunningMap) = runningTaskMap partition {
      case (k, v) =>
        toKillMap.contains(k)
    }
    // overall number of tasks that need to be killed
    val killCount = math.max(runningTasks.size - scaleTo, sentencedAndRunningMap.size)
    // tasks that should be killed to meet constraints – pass notSentenced & consider the sentenced 'already killed'
    val killToMeetConstraints = meetConstraints(
      notSentencedAndRunningMap.values,
      killCount - sentencedAndRunningMap.size
    )
    // rest are tasks that are not sentenced and need not be killed to meet constraints
    val rest = notSentencedAndRunningMap -- killToMeetConstraints.map(_.instanceId)

    // TODO: this should evaluate a task's health as well
    // If we need to kill tasks, the order should be LOST - UNREACHABLE - UNHEALTHY - STAGING - (EVERYTHING ELSE)
    def sortByStatusAndDate(a: Instance, b: Instance): Boolean = {
      import SortHelper._
      val weightA = weight(a.state.status)
      val weightB = weight(b.state.status)

      if (weightA < weightB) true
      else if (weightB < weightA) false
      else startedAt(a) > startedAt(b)
    }

    val ordered =
      sentencedAndRunningMap.values.toSeq ++
        killToMeetConstraints.toSeq ++
        rest.values.toSeq.sortWith(sortByStatusAndDate)

    val candidatesToKill = ordered.take(killCount)
    val numberOfTasksToStart = scaleTo - runningTasks.size + killCount

    val tasksToKill = if (candidatesToKill.nonEmpty) Some(candidatesToKill) else None
    val tasksToStart = if (numberOfTasksToStart > 0) Some(numberOfTasksToStart) else None

    ScalingProposition(tasksToKill, tasksToStart)
  }

}

private[this] object SortHelper {
  /** tasks with lower weight should be killed first */
  val weight: Map[InstanceStatus, Int] = Map[InstanceStatus, Int](
    InstanceStatus.Unreachable -> 1,
    InstanceStatus.Staging -> 2,
    InstanceStatus.Starting -> 3,
    InstanceStatus.Running -> 4).withDefaultValue(5)

  def startedAt(instance: Instance): Timestamp = {
    instance.tasks.map(_.launched.flatMap(_.status.startedAt).getOrElse(Timestamp.zero)).min // TODO PODs discuss
  }
}
