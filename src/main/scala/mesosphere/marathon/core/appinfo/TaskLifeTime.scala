package mesosphere.marathon.core.appinfo

import mesosphere.marathon.core.appinfo.impl.TaskForStatistics
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import scala.collection.immutable.Seq

/**
  * Statistical information about task life times.
  *
  * The task life times are measured relative to the stagedAt time.
  */
case class TaskLifeTime(
  averageSeconds: Double,
  medianSeconds: Double)

object TaskLifeTime {
  def forSomeTasks(now: Timestamp, tasks: Seq[Task]): Option[TaskLifeTime] = {
    forSomeTasks(TaskForStatistics.forTasks(now, tasks, Map.empty))
  }

  def forSomeTasks(tasks: Seq[TaskForStatistics]): Option[TaskLifeTime] = {
    val lifeTimes = tasks.iterator.flatMap(_.maybeLifeTime).toVector.sorted

    if (lifeTimes.isEmpty) {
      None
    } else {
      Some(
        TaskLifeTime(
          averageSeconds = lifeTimes.sum / lifeTimes.size,
          medianSeconds = lifeTimes(lifeTimes.size / 2)
        )
      )
    }
  }
}

