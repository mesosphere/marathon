package mesosphere.marathon.core.appinfo.impl

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.health.Health
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskState

import scala.collection.mutable

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
    tasks: Iterable[Task],
    statuses: Map[Task.Id, Seq[Health]]): Iterable[TaskForStatistics] = {

    val nowTs: Long = now.toDateTime.getMillis

    def taskForStatistics(task: Task): Option[TaskForStatistics] = {
      task.launched.map { launched =>
        val maybeTaskState = launched.status.mesosStatus.map(_.getState)
        val healths = statuses.getOrElse(task.taskId, Seq.empty)
        val maybeTaskLifeTime = launched.status.startedAt.map { startedAt =>
          (nowTs - startedAt.toDateTime.getMillis) / 1000.0
        }
        new TaskForStatistics(
          version = launched.appVersion,
          running = maybeTaskState.contains(TaskState.TASK_RUNNING),
          // Tasks that are staged do not have the taskState set at all, currently.
          // To make this a bit more robust, we also allow it to be set explicitly.
          staging = maybeTaskState.isEmpty || maybeTaskState.contains(TaskState.TASK_STAGING),
          healthy = healths.nonEmpty && healths.forall(_.alive),
          unhealthy = healths.exists(!_.alive),
          maybeLifeTime = maybeTaskLifeTime
        )
      }
    }

    tasks.iterator.flatMap(taskForStatistics).toVector
  }
}
