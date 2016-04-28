package mesosphere.marathon.core.task.update.impl.steps

import javax.inject.Inject

import com.google.inject.Provider
import mesosphere.marathon.core.jobs.JobScheduler
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep

import scala.concurrent.Future

// FIXME (Jobs): remove Impl suffix from steps
class NotifyJobSchedulerStepImpl @Inject() (jobSchedulerProvider: Provider[JobScheduler]) extends TaskUpdateStep {
  override def name: String = "notifyJobScheduler"

  private[this] lazy val jobScheduler = jobSchedulerProvider.get()

  override def processUpdate(taskChanged: TaskChanged): Future[_] = {
    jobScheduler.notifyOfTaskChanged(taskChanged)
  }
}
