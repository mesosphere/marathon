package mesosphere.marathon
package core.appinfo

import mesosphere.marathon.core.appinfo.impl.TaskForStatistics

/**
  * Statistical information about task life times.
  *
  * The task life times are measured relative to the stagedAt time.
  */
object TaskLifeTime {

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
