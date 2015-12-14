package mesosphere.marathon.upgrade

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.tasks.MarathonTasks

case class ScalingProposition(tasksToKill: Option[Seq[MarathonTask]], tasksToStart: Option[Int])

object ScalingProposition {
  def propose(runningTasks: Iterable[MarathonTask],
              toKill: Option[Iterable[MarathonTask]],
              meetConstraints: ((Iterable[MarathonTask], Int) => Iterable[MarathonTask]),
              scaleTo: Int): ScalingProposition = {

    val runningTaskMap = MarathonTasks.taskMap(runningTasks)
    val toKillMap = MarathonTasks.taskMap(toKill.getOrElse(Set.empty))

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
    val rest = notSentencedAndRunningMap -- killToMeetConstraints.map(_.getId)
    val ordered =
      sentencedAndRunningMap.values.toSeq ++
        killToMeetConstraints.toSeq ++
        rest.values.toSeq.sortBy(_.getStartedAt).reverse

    val candidatesToKill = ordered.take(killCount)
    val numberOfTasksToStart = scaleTo - runningTasks.size + killCount

    val tasksToKill = if (candidatesToKill.nonEmpty) Some(candidatesToKill) else None
    val tasksToStart = if (numberOfTasksToStart > 0) Some(numberOfTasksToStart) else None

    ScalingProposition(tasksToKill, tasksToStart)
  }

}
