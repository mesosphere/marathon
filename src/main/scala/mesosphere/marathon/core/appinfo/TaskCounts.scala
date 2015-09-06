package mesosphere.marathon.core.appinfo

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.appinfo.impl.TaskForStatistics
import mesosphere.marathon.health.Health
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.{ Protos => mesos }

/**
  * @param tasksStaged snapshot of the number of staged tasks
  * @param tasksRunning snapshot of the number of running tasks
  * @param tasksHealthy snapshot of the number of healthy tasks (does not include tasks without health info)
  * @param tasksUnhealthy snapshot of the number of unhealthy tasks (does not include tasks without health info)
  */
case class TaskCounts(
  tasksStaged: Int,
  tasksRunning: Int,
  tasksHealthy: Int,
  tasksUnhealthy: Int)

object TaskCounts {
  def zero: TaskCounts = TaskCounts(tasksStaged = 0, tasksRunning = 0, tasksHealthy = 0, tasksUnhealthy = 0)

  def apply(appTasks: Iterable[MarathonTask], statuses: Map[String, Seq[Health]]): TaskCounts = {
    TaskCounts(TaskForStatistics.forTasks(Timestamp(0), appTasks, statuses))
  }

  def apply(appTasks: Iterable[TaskForStatistics]): TaskCounts = {
    TaskCounts(
      tasksStaged = appTasks.count(_.staging),
      tasksRunning = appTasks.count(_.running),
      tasksHealthy = appTasks.count(_.healthy),
      tasksUnhealthy = appTasks.count(_.unhealthy)
    )
  }
}
