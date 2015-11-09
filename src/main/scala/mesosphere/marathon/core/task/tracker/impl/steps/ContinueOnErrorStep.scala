package mesosphere.marathon.core.task.tracker.impl.steps

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.TaskStatusUpdateStep
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Log errors in the wrapped step but do not fail because of them.
  */
class ContinueOnErrorStep(wrapped: TaskStatusUpdateStep) extends TaskStatusUpdateStep {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def name: String = s"continueOnError(${wrapped.name})"

  override def processUpdate(
    timestamp: Timestamp, appId: PathId, maybeTask: Option[MarathonTask], mesosStatus: TaskStatus): Future[_] = {

    import scala.concurrent.ExecutionContext.Implicits.global
    wrapped.processUpdate(timestamp, appId, maybeTask, mesosStatus).recover {
      case NonFatal(e) =>
        log.error("while executing step {} for [{}], continue with other steps",
          wrapped.name, mesosStatus.getTaskId.getValue, e)
    }
  }
}

object ContinueOnErrorStep {
  def apply(wrapped: TaskStatusUpdateStep): ContinueOnErrorStep = new ContinueOnErrorStep(wrapped)
}
