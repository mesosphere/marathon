package mesosphere.marathon.upgrade

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.state.Timestamp

case class ScalingProposition(tasksToKill: Option[Seq[Task]], tasksToStart: Option[Int])

object ScalingProposition {
  def propose(
    runningTasks: Iterable[Task],
    toKill: Option[Iterable[Task]],
    meetConstraints: ((Iterable[Task], Int) => Iterable[Task]),
    scaleTo: Int): ScalingProposition = {

    // TODO: tasks in state KILLING shouldn't be killed and should decrease the amount to kill
    val runningTaskMap = Task.tasksById(runningTasks)
    val toKillMap = Task.tasksById(toKill.getOrElse(Set.empty))

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
    val rest = notSentencedAndRunningMap -- killToMeetConstraints.map(_.taskId)

    // TODO: this should evaluate a task's health as well
    // If we need to kill tasks, the order should be LOST - UNREACHABLE - UNHEALTHY - STAGING - (EVERYTHING ELSE)
    def sortByStatusAndDate(a: Task, b: Task): Boolean = {
      import SortHelper._
      val weightA = weight(a.status.taskStatus)
      val weightB = weight(b.status.taskStatus)

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
  val weight: Map[MarathonTaskStatus, Int] = Map[MarathonTaskStatus, Int](
    MarathonTaskStatus.Unreachable -> 1,
    MarathonTaskStatus.Staging -> 2,
    MarathonTaskStatus.Starting -> 3,
    MarathonTaskStatus.Running -> 4).withDefaultValue(5)

  def startedAt(task: Task): Timestamp = {
    task.launched.flatMap(_.status.startedAt).getOrElse(Timestamp.zero)
  }
}
