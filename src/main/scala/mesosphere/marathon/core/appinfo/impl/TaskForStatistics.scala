package mesosphere.marathon.core.appinfo.impl

import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.instance.Instance
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
    tasks: Iterable[Instance],
    statuses: Map[Instance.Id, Seq[Health]]): Iterable[TaskForStatistics] = {

    val nowTs: Long = now.toDateTime.getMillis

    def taskForStatistics(instance: Instance): Option[TaskForStatistics] = {
      instance match {
        case task: Task =>
          task.launched.map { launched =>
            val maybeTaskState = launched.status.mesosStatus.map(_.getState)
            val healths = statuses.getOrElse(task.id, Seq.empty)
            val maybeTaskLifeTime = launched.status.startedAt.map { startedAt =>
              (nowTs - startedAt.toDateTime.getMillis) / 1000.0
            }
            new TaskForStatistics(
              version = launched.runSpecVersion,
              running = maybeTaskState.contains(TaskState.TASK_RUNNING),
              // Tasks that are staged do not have the taskState set at all, currently.
              // To make this a bit more robust, we also allow it to be set explicitly.
              staging = maybeTaskState.isEmpty || maybeTaskState.contains(TaskState.TASK_STAGING),
              healthy = healths.nonEmpty && healths.forall(_.alive),
              unhealthy = healths.exists(!_.alive),
              maybeLifeTime = maybeTaskLifeTime
            )
          }
        case _ => None // TODO POD support
      }
    }

    tasks.iterator.flatMap(taskForStatistics).toVector
  }
}
