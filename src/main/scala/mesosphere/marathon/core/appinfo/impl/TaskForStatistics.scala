package mesosphere.marathon
package core.appinfo.impl

import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskState

/** Precalculated task infos for internal calculations. */
private[appinfo] class TaskForStatistics(
  val version: Timestamp,
  val running: Boolean,
  val staging: Boolean,
  val healthy: Boolean,
  val unhealthy: Boolean,
  val maybeLifeTime: Option[Double])

private[appinfo] object TaskForStatistics {
  def forTasks(
    now: Timestamp,
    tasks: Seq[Task],
    statuses: Map[Task.Id, Seq[Health]]): Seq[TaskForStatistics] = {

    val nowTs: Long = now.toDateTime.getMillis

    def taskForStatistics(task: Task): TaskForStatistics = {
      val maybeTaskState = task.status.mesosStatus.map(_.getState)
      val healths = statuses.getOrElse(task.taskId, Seq.empty)
      val maybeTaskLifeTime = task.status.startedAt.map { startedAt =>
        (nowTs - startedAt.toDateTime.getMillis) / 1000.0
      }
      new TaskForStatistics(
        version = task.runSpecVersion,
        running = maybeTaskState.contains(TaskState.TASK_RUNNING),
        // Tasks that are staged do not have the taskState set at all, currently.
        // To make this a bit more robust, we also allow it to be set explicitly.
        staging = maybeTaskState.isEmpty || maybeTaskState.contains(TaskState.TASK_STAGING),
        healthy = healths.nonEmpty && healths.forall(_.alive),
        unhealthy = healths.exists(!_.alive),
        maybeLifeTime = maybeTaskLifeTime
      )
    }

    tasks.map(taskForStatistics)
  }
}
