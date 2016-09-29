package mesosphere.marathon.core.task.update.impl.steps
//scalastyle:off
import javax.inject.Named

import akka.Done
import akka.actor.ActorRef
import com.google.inject.{ Inject, Provider }
import mesosphere.marathon.MarathonSchedulerActor.ScaleRunSpec
import mesosphere.marathon.core.instance.InstanceStatus
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceChangeHandler }
import org.slf4j.LoggerFactory

import scala.concurrent.Future
//scalastyle:on
/**
  * Trigger rescale of affected app if a task died or a reserved task timed out.
  */
class ScaleAppUpdateStepImpl @Inject() (
    @Named("schedulerActor") schedulerActorProvider: Provider[ActorRef]) extends InstanceChangeHandler {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] lazy val schedulerActor = schedulerActorProvider.get()

  override def name: String = "scaleApp"

  override def process(update: InstanceChange): Future[Done] = {
    // TODO(PODS): it should be up to a tbd TaskUnreachableBehavior how to handle Unreachable
    update.status match {
      case InstanceStatus.Reserved | InstanceStatus.Unreachable | InstanceStatus.Terminal(_) =>
        val runSpecId = update.runSpecId
        val instanceId = update.id
        val state = update.status
        log.info(s"initiating a scale check for runSpec [$runSpecId] due to [$instanceId] $state")
        // TODO(PODS): we should rename the Message and make the SchedulerActor generic
        schedulerActor ! ScaleRunSpec(runSpecId)

      case _ =>
      // nothing
    }

    Future.successful(Done)
  }
}
