package mesosphere.marathon.core.task.tracker.impl.steps

import javax.inject.Inject

import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.TaskStatusUpdateStep
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

/**
  * Send acknowledgement for this update to Mesos.
  */
class AcknowledgeTaskUpdateStepImpl @Inject() (marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder)
    extends TaskStatusUpdateStep {
  override def name: String = "acknowledgeTaskUpdate"

  override def processUpdate(
    timestamp: Timestamp, appId: PathId, maybeTask: Option[MarathonTask], status: TaskStatus): Future[_] = {
    driverOpt.foreach(_.acknowledgeStatusUpdate(status))
    Future.successful(())
  }

  private[this] def driverOpt = marathonSchedulerDriverHolder.driver
}
