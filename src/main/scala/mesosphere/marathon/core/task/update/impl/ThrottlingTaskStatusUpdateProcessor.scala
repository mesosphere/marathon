package mesosphere.marathon.core.task.update.impl

import javax.inject.{ Inject, Named }

import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.util.CapConcurrentExecutions
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

object ThrottlingTaskStatusUpdateProcessor {
  /**
    * A tag used for dependency injection to disambiguate the dependencies of this processor from
    * other instances with the same type.
    */
  final val dependencyTag = "ThrottlingTaskStatusUpdateProcessor"
}

private[core] class ThrottlingTaskStatusUpdateProcessor @Inject() (
  @Named(ThrottlingTaskStatusUpdateProcessor.dependencyTag) serializePublish: CapConcurrentExecutions,
  @Named(ThrottlingTaskStatusUpdateProcessor.dependencyTag) wrapped: TaskStatusUpdateProcessor)
    extends TaskStatusUpdateProcessor {
  override def publish(status: TaskStatus): Future[Unit] = serializePublish(wrapped.publish(status))
}
