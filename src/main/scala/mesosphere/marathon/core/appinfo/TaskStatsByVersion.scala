package mesosphere.marathon
package core.appinfo

import mesosphere.marathon.core.appinfo.impl.TaskForStatistics
import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.VersionInfo._
import mesosphere.marathon.state.{ Timestamp, VersionInfo }

case class TaskStatsByVersion(
  maybeStartedAfterLastScaling: Option[TaskStats],
  maybeWithLatestConfig: Option[TaskStats],
  maybeWithOutdatedConfig: Option[TaskStats],
  maybeTotalSummary: Option[TaskStats])

object TaskStatsByVersion {

  def apply(
    versionInfo: VersionInfo,
    tasks: Seq[TaskForStatistics]): TaskStatsByVersion =
    {
      def statsForVersion(versionTest: Timestamp => Boolean): Option[TaskStats] = {
        TaskStats.forSomeTasks(tasks.filter(task => versionTest(task.version)))
      }

      val maybeFullVersionInfo = versionInfo match {
        case full: FullVersionInfo => Some(full)
        case _ => None
      }

      TaskStatsByVersion(
        maybeTotalSummary = TaskStats.forSomeTasks(tasks),
        maybeStartedAfterLastScaling = maybeFullVersionInfo.flatMap { vi =>
          statsForVersion(_ >= vi.lastScalingAt)
        },
        maybeWithLatestConfig = maybeFullVersionInfo.flatMap { vi =>
          statsForVersion(_ >= vi.lastConfigChangeAt)
        },
        maybeWithOutdatedConfig = maybeFullVersionInfo.flatMap { vi =>
          statsForVersion(_ < vi.lastConfigChangeAt)
        }
      )

    }

  def apply(
    now: Timestamp,
    versionInfo: VersionInfo,
    instances: Seq[Instance],
    statuses: Map[Instance.Id, Seq[Health]]): TaskStatsByVersion =
    {
      TaskStatsByVersion(versionInfo, TaskForStatistics.forInstances(now, instances, statuses))
    }
}

case class TaskStats(
  counts: TaskCounts,
  maybeLifeTime: Option[TaskLifeTime])

object TaskStats {
  def forSomeTasks(
    now: Timestamp, instances: Seq[Instance], statuses: Map[Instance.Id, Seq[Health]]): Option[TaskStats] =
    {
      forSomeTasks(TaskForStatistics.forInstances(now, instances, statuses))
    }

  def forSomeTasks(tasks: Seq[TaskForStatistics]): Option[TaskStats] = {
    if (tasks.isEmpty) {
      None
    } else {
      Some(
        TaskStats(
          counts = TaskCounts(tasks),
          maybeLifeTime = TaskLifeTime.forSomeTasks(tasks)
        )
      )
    }
  }
}
