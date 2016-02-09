package mesosphere.marathon.core.appinfo

import mesosphere.marathon.core.appinfo.impl.TaskForStatistics
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.health.Health
import mesosphere.marathon.state.AppDefinition.VersionInfo
import mesosphere.marathon.state.AppDefinition.VersionInfo.FullVersionInfo
import mesosphere.marathon.state.Timestamp

case class TaskStatsByVersion(
  maybeStartedAfterLastScaling: Option[TaskStats],
  maybeWithLatestConfig: Option[TaskStats],
  maybeWithOutdatedConfig: Option[TaskStats],
  maybeTotalSummary: Option[TaskStats])

object TaskStatsByVersion {

  def apply(
    versionInfo: VersionInfo,
    tasks: Iterable[TaskForStatistics]): TaskStatsByVersion =
    {
      def statsForVersion(versionTest: Timestamp => Boolean): Option[TaskStats] = {
        TaskStats.forSomeTasks(tasks.filter(task => versionTest(task.version)))
      }

      val maybeFullVersionInfo = versionInfo match {
        case full: FullVersionInfo => Some(full)
        case _                     => None
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
    tasks: Iterable[Task],
    statuses: Map[Task.Id, Seq[Health]]): TaskStatsByVersion =
    {
      TaskStatsByVersion(versionInfo, TaskForStatistics.forTasks(now, tasks, statuses))
    }
}

case class TaskStats(
  counts: TaskCounts,
  maybeLifeTime: Option[TaskLifeTime])

object TaskStats {
  def forSomeTasks(
    now: Timestamp, tasks: Iterable[Task], statuses: Map[Task.Id, Seq[Health]]): Option[TaskStats] =
    {
      forSomeTasks(TaskForStatistics.forTasks(now, tasks, statuses))
    }

  def forSomeTasks(tasks: Iterable[TaskForStatistics]): Option[TaskStats] = {
    if (tasks.isEmpty) {
      None
    }
    else {
      Some(
        TaskStats(
          counts = TaskCounts(tasks),
          maybeLifeTime = TaskLifeTime.forSomeTasks(tasks)
        )
      )
    }
  }
}
