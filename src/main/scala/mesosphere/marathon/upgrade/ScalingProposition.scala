package mesosphere.marathon.upgrade

import mesosphere.marathon.Protos.MarathonTask

case class ScalingProposition(tasksToKill: Option[Seq[MarathonTask]], tasksToStart: Option[Int])

object ScalingProposition {
  def propose(runningTasks: Set[MarathonTask],
              toKill: Option[Set[MarathonTask]],
              meetConstraints: ((Set[MarathonTask], Int) => Set[MarathonTask]),
              scaleTo: Int): ScalingProposition = {
    val (sentencedAndRunning, notSentencedAndRunning) = runningTasks partition toKill.getOrElse(Set.empty)
    // overall number of tasks that need to be killed
    val killCount = math.max(runningTasks.size - scaleTo, sentencedAndRunning.size)
    // tasks that should be killed to meet constraints – pass notSentenced & consider the sentenced 'already killed'
    val killToMeetConstraints = meetConstraints(notSentencedAndRunning, killCount - sentencedAndRunning.size)
    // rest are tasks that are not sentenced and need not be killed to meet constraints
    val rest = notSentencedAndRunning diff killToMeetConstraints
    val ordered =
      sentencedAndRunning.toSeq ++
        killToMeetConstraints.toSeq ++
        rest.toSeq.sortBy(_.getStartedAt).reverse

    val candidatesToKill = ordered.take(killCount)
    val numberOfTasksToStart = scaleTo - runningTasks.size + killCount

    val tasksToKill = if (candidatesToKill.nonEmpty) Some(candidatesToKill) else None
    val tasksToStart = if (numberOfTasksToStart > 0) Some(numberOfTasksToStart) else None

    ScalingProposition(tasksToKill, tasksToStart)
  }

}
