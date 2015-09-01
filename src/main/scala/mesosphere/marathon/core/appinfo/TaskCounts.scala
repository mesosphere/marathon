package mesosphere.marathon.core.appinfo

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.health.HealthCounts
import org.apache.mesos.{ Protos => mesos }

/**
  * @param tasksStaged snapshot of the number of staged tasks
  * @param tasksRunning snapshot of the number of running tasks
  * @param tasksHealthy snapshot of the number of healthy tasks
  * @param tasksUnhealthy snapshot of the number of unhealthy tasks
  */
case class TaskCounts(
  tasksStaged: Int,
  tasksRunning: Int,
  tasksHealthy: Int,
  tasksUnhealthy: Int)

object TaskCounts {
  def zero: TaskCounts = TaskCounts(tasksStaged = 0, tasksRunning = 0, tasksHealthy = 0, tasksUnhealthy = 0)

  def apply(appTasks: Iterable[MarathonTask], healthCounts: HealthCounts): TaskCounts = {
    def countByStatus(state: mesos.TaskState): Int = {
      appTasks.count { task => task.hasStatus && task.getStatus.getState == state }
    }

    TaskCounts(
      tasksStaged = countByStatus(mesos.TaskState.TASK_STAGING),
      tasksRunning = countByStatus(mesos.TaskState.TASK_RUNNING),
      tasksHealthy = healthCounts.healthy,
      tasksUnhealthy = healthCounts.unhealthy
    )
  }
}
