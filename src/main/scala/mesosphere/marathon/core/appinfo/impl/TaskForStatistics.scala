package mesosphere.marathon
package core.appinfo.impl

import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskState

/** Precalculated instance infos for internal calculations. */
// TODO: rename to InstanceForStatistics?
private[appinfo] class TaskForStatistics(
    val version: Timestamp,
    val running: Boolean,
    val staging: Boolean,
    val healthy: Boolean,
    val unhealthy: Boolean,
    val maybeLifeTime: Option[Double])

private[appinfo] object TaskForStatistics {
  def forInstances(
    now: Timestamp,
    instances: Seq[Instance],
    statuses: Map[Instance.Id, Seq[Health]]): Seq[TaskForStatistics] = {

    val nowTs: Long = now.millis

    def taskForStatistics(instance: Instance): TaskForStatistics = {
      // TODO (ME): assuming statistics make no sense for pod containers – a task in a pod might finish after 10 seconds
      // while the remaining task continues to run. statistics should be based on instances imo.
      val task: Task = instance.appTask
      val maybeTaskState = task.status.mesosStatus.map(_.getState)
      val healths = statuses.getOrElse(instance.instanceId, Seq.empty)
      val maybeTaskLifeTime = task.status.startedAt.map { startedAt =>
        (nowTs - startedAt.millis) / 1000.0
      }
      new TaskForStatistics(
        version = instance.runSpecVersion,
        running = maybeTaskState.contains(TaskState.TASK_RUNNING),
        // Tasks that are staged do not have the taskState set at all, currently.
        // To make this a bit more robust, we also allow it to be set explicitly.
        staging = maybeTaskState.isEmpty || maybeTaskState.contains(TaskState.TASK_STAGING),
        healthy = healths.nonEmpty && healths.forall(_.alive),
        unhealthy = healths.exists(!_.alive),
        maybeLifeTime = maybeTaskLifeTime
      )
    }

    instances.map(taskForStatistics)
  }
}
