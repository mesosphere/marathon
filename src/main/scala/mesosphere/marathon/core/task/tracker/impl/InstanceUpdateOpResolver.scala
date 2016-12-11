package mesosphere.marathon
package core.task.tracker.impl

import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstanceChangedEventsGenerator, InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Maps a [[InstanceUpdateOperation]] to the appropriate [[InstanceUpdateEffect]].
  *
  * @param directInstanceTracker a TaskTracker instance that goes directly to the correct taskTracker
  *                          without going through the WhenLeaderActor indirection.
  */
private[tracker] class InstanceUpdateOpResolver(directInstanceTracker: InstanceTracker, clock: Clock) {
  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] val eventsGenerator = InstanceChangedEventsGenerator

  /**
    * Maps the TaskStateOp
    *
    * * a TaskStateChange.Failure if the task does not exist OR ELSE
    * * delegates the TaskStateOp to the existing task that will then determine the state change
    */
  def resolve(op: InstanceUpdateOperation)(implicit ec: ExecutionContext): Future[InstanceUpdateEffect] = {
    op match {
      case op: InstanceUpdateOperation.LaunchEphemeral => updateIfNotExists(op.instanceId, op.instance)
      case op: InstanceUpdateOperation.LaunchOnReservation => updateExistingInstance(op)
      case op: InstanceUpdateOperation.MesosUpdate => updateExistingInstance(op)
      case op: InstanceUpdateOperation.ReservationTimeout => updateExistingInstance(op)
      case op: InstanceUpdateOperation.Reserve => updateIfNotExists(op.instanceId, op.instance)
      case op: InstanceUpdateOperation.ForceExpunge => expungeInstance(op.instanceId)
      case op: InstanceUpdateOperation.Revert =>
        Future.successful(InstanceUpdateEffect.Update(op.instance, oldState = None, events = Nil))
    }
  }

  private[this] def updateIfNotExists(instanceId: Instance.Id, updatedInstance: Instance)(
    implicit
    ec: ExecutionContext): Future[InstanceUpdateEffect] = {
    directInstanceTracker.instance(instanceId).map {
      case Some(existingInstance) =>
        InstanceUpdateEffect.Failure( //
          new IllegalStateException(s"$instanceId of app [${instanceId.runSpecId}] already exists"))

      case None =>
        val events = eventsGenerator.events(updatedInstance, task = None, clock.now(), instanceChanged = true)
        InstanceUpdateEffect.Update(updatedInstance, oldState = None, events)
    }
  }

  private[this] def updateExistingInstance(op: InstanceUpdateOperation) //
  (implicit ec: ExecutionContext): Future[InstanceUpdateEffect] = {
    directInstanceTracker.instance(op.instanceId).map {
      case Some(existingInstance) =>
        existingInstance.update(op)

      case None =>
        val id = op.instanceId
        InstanceUpdateEffect.Failure(new IllegalStateException(s"$id of app [${id.runSpecId}] does not exist"))
    }
  }

  private[this] def expungeInstance(id: Instance.Id)(implicit ec: ExecutionContext): Future[InstanceUpdateEffect] = {
    directInstanceTracker.instance(id).map {
      case Some(existingInstance: Instance) =>
        val events = eventsGenerator.events(Condition.Killed, existingInstance, task = None, clock.now(), instanceChanged = true)
        InstanceUpdateEffect.Expunge(existingInstance, events)

      case None =>
        log.info("Ignoring ForceExpunge for [{}], task does not exist", id)
        InstanceUpdateEffect.Noop(id)
    }
  }
}
