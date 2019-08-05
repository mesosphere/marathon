package mesosphere.marathon
package core.appinfo

import mesosphere.marathon.core.appinfo.impl.TaskForStatistics
import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.VersionInfo._
import mesosphere.marathon.state.{Timestamp, VersionInfo}

//case class TaskStatsByVersion(
//    maybeStartedAfterLastScaling: Option[TaskStats],
//    maybeWithLatestConfig: Option[TaskStats],
//    maybeWithOutdatedConfig: Option[TaskStats],
//    maybeTotalSummary: Option[TaskStats])

object TaskStatsByVersion {

  def apply(
    versionInfo: VersionInfo,
    tasks: Seq[TaskForStatistics]): raml.TaskStatsByVersion =
    {
      def statsForVersion(versionTest: Timestamp => Boolean): Option[raml.TaskStats] = {
        TaskStats.forSomeTasks(tasks.filter(task => versionTest(task.version)))
      }

      val maybeFullVersionInfo = versionInfo match {
        case full: FullVersionInfo => Some(full)
        case _ => None
      }

      raml.TaskStatsByVersion(
        totalSummary = TaskStats.forSomeTasks(tasks),
        startedAfterLastScaling = maybeFullVersionInfo.flatMap { vi =>
          statsForVersion(_ >= vi.lastScalingAt)
        },
        withLatestConfig = maybeFullVersionInfo.flatMap { vi =>
          statsForVersion(_ >= vi.lastConfigChangeAt)
        },
        withOutdatedConfig = maybeFullVersionInfo.flatMap { vi =>
          statsForVersion(_ < vi.lastConfigChangeAt)
        }
      )

    }

  def apply(
    now: Timestamp,
    versionInfo: VersionInfo,
    instances: Seq[Instance],
    statuses: Map[Instance.Id, Seq[Health]]): raml.TaskStatsByVersion =
    {
      TaskStatsByVersion(versionInfo, TaskForStatistics.forInstances(now, instances, statuses))
    }
}

object TaskStats {
  def forSomeTasks(
    now: Timestamp, instances: Seq[Instance], statuses: Map[Instance.Id, Seq[Health]]): Option[raml.TaskStats] =
    {
      forSomeTasks(TaskForStatistics.forInstances(now, instances, statuses))
    }

  def forSomeTasks(tasks: Seq[TaskForStatistics]): Option[raml.TaskStats] = {
    if (tasks.isEmpty) {
      None
    } else {
      val counts = TaskCounts(tasks)
      Some(
        raml.TaskStats(
          counts = raml.TaskCounts(counts.tasksStaged, counts.tasksRunning, counts.tasksHealthy, counts.tasksUnhealthy),
          lifeTime = TaskLifeTime.forSomeTasks(tasks)
        )
      )
    }
  }
}
