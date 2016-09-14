package mesosphere.marathon.core.task.update.impl.steps

import akka.Done
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Log errors in the wrapped step but do not fail because of them.
  */
class ContinueOnErrorStep(wrapped: InstanceChangeHandler) extends InstanceChangeHandler {
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def name: String = s"continueOnError(${wrapped.name})"

  override def process(update: InstanceChange): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val maybeProcessed: Option[Future[Done]] = Option(wrapped.process(update))
    maybeProcessed match {
      case Some(processed) =>
        processed.recover {
          case NonFatal(e) =>
            log.error(
              "while executing step {} for [{}], continue with other steps",
              wrapped.name, update.id.idString, e)
            Done
        }
      case None =>
        log.error(
          "step {} for [{}] returned null, continue with other steps",
          Array[Object](wrapped.name, update.id.idString): _*)
        Future.successful(Done)
    }
  }
}

object ContinueOnErrorStep {
  def apply(wrapped: InstanceChangeHandler): ContinueOnErrorStep = new ContinueOnErrorStep(wrapped)
}
