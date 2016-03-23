package mesosphere.marathon.core.task.update.impl.steps

import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Log errors in the wrapped step but do not fail because of them.
  */
class ContinueOnErrorStep(wrapped: TaskUpdateStep) extends TaskUpdateStep {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def name: String = s"continueOnError(${wrapped.name})"

  override def processUpdate(taskChanged: TaskChanged): Future[_] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val maybeProcessed: Option[Future[_]] = Option(wrapped.processUpdate(taskChanged))
    maybeProcessed match {
      case Some(processed) =>
        processed.recover {
          case NonFatal(e) =>
            log.error("while executing step {} for [{}], continue with other steps",
              wrapped.name, taskChanged.taskId.idString, e)
        }
      case None =>
        log.error("step {} for [{}] returned null, continue with other steps",
          Array[Object](wrapped.name, taskChanged.taskId.idString): _*)
        Future.successful(())
    }
  }
}

object ContinueOnErrorStep {
  def apply(wrapped: TaskUpdateStep): ContinueOnErrorStep = new ContinueOnErrorStep(wrapped)
}
