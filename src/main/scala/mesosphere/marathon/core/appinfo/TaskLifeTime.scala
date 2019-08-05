package mesosphere.marathon
package core.appinfo

import mesosphere.marathon.core.appinfo.impl.TaskForStatistics
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.Timestamp

/**
  * Statistical information about task life times.
  *
  * The task life times are measured relative to the stagedAt time.
  */
object TaskLifeTime {
  def forSomeTasks(now: Timestamp, instances: Seq[Instance]): Option[raml.TaskLifeTime] = {
    forSomeTasks(TaskForStatistics.forInstances(now, instances, Map.empty))
  }

  def forSomeTasks(tasks: Seq[TaskForStatistics]): Option[raml.TaskLifeTime] = {
    val lifeTimes = tasks.flatMap(_.maybeLifeTime).sorted

    if (lifeTimes.isEmpty) {
      None
    } else {
      Some(
        raml.TaskLifeTime(
          averageSeconds = lifeTimes.sum / lifeTimes.size,
          medianSeconds = lifeTimes(lifeTimes.size / 2)
        )
      )
    }
  }
}

