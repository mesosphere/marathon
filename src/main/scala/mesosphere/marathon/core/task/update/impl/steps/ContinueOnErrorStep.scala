package mesosphere.marathon.core.task.update.impl.steps

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.update.TaskStatusUpdateStep
import mesosphere.marathon.state.{ PathId, Timestamp }
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
    timestamp: Timestamp, appId: PathId, task: MarathonTask, mesosStatus: TaskStatus): Future[_] = {

    import scala.concurrent.ExecutionContext.Implicits.global
    val maybeProcessed: Option[Future[_]] = Option(wrapped.processUpdate(timestamp, appId, task, mesosStatus))
    maybeProcessed match {
      case Some(processed) =>
        processed.recover {
          case NonFatal(e) =>
            log.error("while executing step {} for [{}], continue with other steps",
              wrapped.name, mesosStatus.getTaskId.getValue, e)
        }
      case None =>
        log.error("step {} for [{}] returned null, continue with other steps",
          Array[Object](wrapped.name, mesosStatus.getTaskId.getValue): _*)
        Future.successful(())
    }
  }
}

object ContinueOnErrorStep {
  def apply(wrapped: TaskStatusUpdateStep): ContinueOnErrorStep = new ContinueOnErrorStep(wrapped)
}
