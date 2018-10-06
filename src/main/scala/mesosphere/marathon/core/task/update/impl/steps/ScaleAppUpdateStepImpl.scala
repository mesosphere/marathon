package mesosphere.marathon
package core.task.update.impl.steps
//scalastyle:off
import javax.inject.Named

import akka.Done
import akka.actor.ActorRef
import com.google.inject.{Inject, Provider}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.MarathonSchedulerActor.ScaleRunSpec
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceChangeHandler}

import scala.concurrent.Future
//scalastyle:on
/**
  * Trigger rescale of affected app if a task died or a reserved task timed out.
  */
class ScaleAppUpdateStepImpl @Inject() (
    @Named("schedulerActor") schedulerActorProvider: Provider[ActorRef]) extends InstanceChangeHandler with StrictLogging {

  private[this] lazy val schedulerActor = schedulerActorProvider.get()

  private[this] def scalingWorthy: Condition => Boolean = {
    case Condition.Reserved | Condition.UnreachableInactive | _: Condition.Terminal => true
    case _ => false
  }

  override def name: String = "scaleApp"
  override def metricName: String = "scale-app"

  override def process(update: InstanceChange): Future[Done] = {
    // TODO(PODS): it should be up to a tbd TaskUnreachableBehavior how to handle Unreachable
    // TODO MARATHON-8429: this is pretty racy:
    // 1. We would send an event to the schedulerActor
    // 2. The schedulerActor would lock the app and call schedulerActions.scale
    // 3. If one or more InstanceChange are processed before the lock is released, no replacements are scheduled for those
    // The logs say e.g. `Did not try to scale run spec /app-kill-all-tasks-of-an-app; it is locked`
    calcScaleEvent(update).foreach(event => schedulerActor ! event)
    Future.successful(Done)
  }

  def calcScaleEvent(update: InstanceChange): Option[ScaleRunSpec] = {
    if (scalingWorthy(update.condition) && update.lastState.forall(lastState => !scalingWorthy(lastState.condition))) {

      val runSpecId = update.runSpecId
      val instanceId = update.id
      val state = update.condition
      logger.info(s"initiating a scale check for runSpec [$runSpecId] due to [$instanceId] $state")
      // TODO(PODS): we should rename the Message and make the SchedulerActor generic
      Some(ScaleRunSpec(runSpecId))
    } else {
      None
    }
  }
}
