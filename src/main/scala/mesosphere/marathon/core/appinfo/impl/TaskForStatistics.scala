package mesosphere.marathon.core.appinfo.impl

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.health.Health
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskState
import scala.collection.mutable

/** Precalculated task infos for internal calculations. */
private[appinfo] class TaskForStatistics(
  val version: Long,
  val running: Boolean,
  val staging: Boolean,
  val healthy: Boolean,
  val unhealthy: Boolean,
  val maybeLifeTime: Option[Double])

private[appinfo] object TaskForStatistics {
  def forTasks(
    now: Timestamp,
    tasks: Iterable[MarathonTask],
    statuses: Map[String, Seq[Health]]): Iterable[TaskForStatistics] = {

    val nowTs: Long = now.toDateTime.getMillis

    // By profiling I saw that parsing the version is the dominating factor.
    // Many tasks typically have the same version, so it makes sense to cache by the version string.
    val cachedVersions = mutable.HashMap.empty[String, Long]

    def taskForStatistics(task: MarathonTask): TaskForStatistics = {
      val version = cachedVersions.getOrElseUpdate(task.getVersion, Timestamp(task.getVersion).toDateTime.getMillis)
      val maybeTaskState = if (task.hasStatus) Some(task.getStatus.getState) else None
      val healths = statuses.getOrElse(task.getId, Seq.empty)
      val maybeTaskLifeTime = if (task.hasStartedAt) Some((nowTs - task.getStartedAt) / 1000.0) else None
      new TaskForStatistics(
        version = version,
        running = maybeTaskState.contains(TaskState.TASK_RUNNING),
        staging = maybeTaskState.contains(TaskState.TASK_STAGING),
        healthy = healths.nonEmpty && healths.forall(_.alive),
        unhealthy = healths.exists(!_.alive),
        maybeLifeTime = maybeTaskLifeTime
      )
    }

    tasks.iterator.map(taskForStatistics).toVector
  }
}
