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
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceChangeHandler}

import scala.concurrent.Future
//scalastyle:on
/**
  * This logic covers part of our orchestration logic and will ask to check the
  * scale of a service when instances become UnreachableInactive, or come back
  * from that state into one that is considered active.
  *
  * The result will be a ScaleRunSpec directive. This will be ignored during
  * ongoing deployments for the service. In this case, the responsible
  * deployment logic will have to handle these cases.
  */
class ScaleAppUpdateStepImpl @Inject() (
    @Named("schedulerActor") schedulerActorProvider: Provider[ActorRef]) extends InstanceChangeHandler with StrictLogging {

  private[this] lazy val schedulerActor = schedulerActorProvider.get()

  override def name: String = "scaleApp"
  override def metricName: String = "scale-app"

  override def process(update: InstanceChange): Future[Done] = {
    // This doesn't block on the result of a scale check, which means that
    // fast subsequent instance changes for the same PathId will eventually be
    // ignored, since the pathId might be locked from a previous ScaleRunSpec
    // attempt. This happened in tests, so it should be considered a real problem.
    // 1. We would send an event to the schedulerActor
    // 2. The schedulerActor would lock the app and call schedulerActions.scale
    // 3. If one or more InstanceChange are processed before the lock is released, no replacements are scheduled for those
    // The logs say e.g. `Did not try to scale run spec /app-kill-all-tasks-of-an-app; it is locked`
    // An option would be to block here until the scale request is acked.
    update.lastState.foreach { lastState =>
      calcScaleEvent(update.id, lastState.condition, update.condition).foreach(event => schedulerActor ! event)
    }
    Future.successful(Done)
  }

  def calcScaleEvent(instanceId: Instance.Id, lastCondition: Condition, newCondition: Condition): Option[ScaleRunSpec] = {
    if (lastCondition == Condition.UnreachableInactive || newCondition == Condition.UnreachableInactive) {
      // if the instance was UnreachableInactive or just became UnreachableInactive, we need to adjust the scale
      logger.info(s"initiating a scale check since $instanceId turned $newCondition (was: $lastCondition)")
      Some(ScaleRunSpec(instanceId.runSpecId))
    } else {
      logger.debug(s"Ignoring $instanceId state change: $lastCondition -> $newCondition. Not scaling worthy.")
      None
    }
  }
}