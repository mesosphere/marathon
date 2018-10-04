package mesosphere.marathon
package core.instance.update

import java.time.Clock

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation._
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.Timestamp

/**
  * Maps a [[InstanceUpdateOperation]] to the appropriate [[InstanceUpdateEffect]].
  */
private[marathon] class InstanceUpdateOpResolver(clock: Clock) extends StrictLogging {

  private[this] val updater = InstanceUpdater

  /**
    * Depending on the type of [[InstanceUpdateOperation]], this will verify that the instance
    * exists (if the operation must be applied to an instance), or that the instance does not
    * yet exist (if the operation effectively creates a new instance). If this prerequisite is
    * violated the future will fail with an [[IllegalStateException]], otherwise the operation
    * will be applied and result in an [[InstanceUpdateEffect]].
    */
  def resolve(instancesBySpec: InstancesBySpec, op: InstanceUpdateOperation): InstanceUpdateEffect = {
    op match {
      case op: Schedule =>
        // TODO(karsten): Create events
        createInstance(instancesBySpec, op.instanceId){
          InstanceUpdateEffect.Update(op.instance, oldState = None, Seq.empty)
        }

      case op: LaunchEphemeral =>
        createInstance(instancesBySpec, op.instanceId)(updater.launchEphemeral(op, clock.now()))

      case op: Provision =>
        updateExistingInstance(instancesBySpec, op.instanceId) { oldInstance =>
          // TODO(karsten): Create events
          InstanceUpdateEffect.Update(op.instance, oldState = Some(oldInstance), Seq.empty)
        }

      case RescheduleReserved(instance, runSpecVersion) =>
        // TODO(alena): Create events
        updateExistingInstance(instancesBySpec, op.instanceId) { i =>
          InstanceUpdateEffect.Update(
            i.copy(
              state = InstanceState(Condition.Scheduled, Timestamp.now(), None, None, Goal.Running),
              runSpecVersion = runSpecVersion,
              unreachableStrategy = instance.unreachableStrategy),
            oldState = Some(i), Seq.empty)
        }

      case op: MesosUpdate =>
        updateExistingInstance(instancesBySpec, op.instanceId)(updater.mesosUpdate(_, op))

      case op: ReservationTimeout =>
        updateExistingInstance(instancesBySpec, op.instanceId)(updater.reservationTimeout(_, clock.now()))

      case op: GoalChange =>
        updateExistingInstance(instancesBySpec, op.instanceId)(i => {
          val updatedInstance = i.copy(state = i.state.copy(goal = op.goal))
          val events = InstanceChangedEventsGenerator.events(updatedInstance, task = None, clock.now(), previousCondition = Some(i.state.condition))

          logger.info(s"Updating goal of instance ${i.instanceId} to ${op.goal}")
          InstanceUpdateEffect.Update(updatedInstance, oldState = Some(i), events = Nil)
        })

      case op: Reserve =>
        updateExistingInstance(instancesBySpec, op.instanceId) { _ =>
          updater.reserve(op, clock.now())
        }

      case op: ForceExpunge =>
        instancesBySpec.instance(op.instanceId) match {
          case Some(existingInstance) =>
            updater.forceExpunge(existingInstance, clock.now())

          case None =>
            InstanceUpdateEffect.Noop(op.instanceId)
        }

      case op: Revert =>
        updater.revert(op.instance)
    }
  }

  /**
    * Helper method that verifies that an instance already exists. If it does, it will apply the given function and
    * return the resulting effect; otherwise this will result in a. [[InstanceUpdateEffect.Failure]].
    * @param id ID of the instance that is expected to exist.
    * @param applyOperation the operation that shall be applied to the instance
    * @return The [[InstanceUpdateEffect]] that results from applying the given operation.
    */
  private[this] def updateExistingInstance(instancesBySpec: InstancesBySpec, id: Instance.Id)(applyOperation: Instance => InstanceUpdateEffect): InstanceUpdateEffect = {
    instancesBySpec.instance(id) match {
      case Some(existingInstance) =>
        applyOperation(existingInstance)

      case None =>
        InstanceUpdateEffect.Failure(
          new IllegalStateException(s"$id of app [${id.runSpecId}] does not exist"))
    }
  }

  /**
    * Helper method that verifies that no instance with the given ID exists, and applies the given operation if that is
    * true. If an instance with this ID already exists, this will result in an [[InstanceUpdateEffect.Failure]].
    * @param id ID of the instance that shall be created.
    * @param applyOperation the operation that will create the instance.
    * @return The [[InstanceUpdateEffect]] that results from applying the given operation.
    */
  private[this] def createInstance(instancesBySpec: InstancesBySpec, id: Instance.Id)(applyOperation: => InstanceUpdateEffect): InstanceUpdateEffect = {
    instancesBySpec.instance(id) match {
      case Some(_) =>
        InstanceUpdateEffect.Failure(
          new IllegalStateException(s"$id of app [${id.runSpecId}] already exists"))

      case None =>
        applyOperation
    }
  }

}
