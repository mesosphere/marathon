package mesosphere.marathon
package core.instance.update

import java.time.Clock

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation._
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
  def resolve(maybeInstance: Option[Instance], op: InstanceUpdateOperation): InstanceUpdateEffect = {
    op match {
      case op: Schedule =>
        // TODO(karsten): Create events
        createInstance(maybeInstance){
          InstanceUpdateEffect.Update(op.instance, oldState = None, Seq.empty)
        }

      case op: Provision =>
        updateExistingInstance(maybeInstance, op.instanceId) { oldInstance =>
          // TODO(karsten): Create events
          val updatedInstance = oldInstance.provisioned(op.agentInfo, op.runSpec, op.tasks, op.now)
          InstanceUpdateEffect.Update(updatedInstance, oldState = Some(oldInstance), Seq.empty)
        }

      case RescheduleReserved(instance, runSpec) =>
        // TODO(alena): Create events
        updateExistingInstance(maybeInstance, op.instanceId) { i =>
          InstanceUpdateEffect.Update(
            i.copy(state = InstanceState(Condition.Scheduled, Timestamp.now(), None, None, Goal.Running), runSpec = runSpec),
            oldState = Some(i),
            events = Seq.empty
          )
        }

      case op: MesosUpdate =>
        updateExistingInstance(maybeInstance, op.instanceId)(updater.mesosUpdate(_, op))

      case op: ReservationTimeout =>
        updateExistingInstance(maybeInstance, op.instanceId)(updater.reservationTimeout(_, clock.now()))

      case op: ChangeGoal =>
        updateExistingInstance(maybeInstance, op.instanceId)(updater.changeGoal(_, op, clock.now()))

      case op: Reserve =>
        updateExistingInstance(maybeInstance, op.instanceId)(_ => updater.reserve(op, clock.now()))

      case op: ForceExpunge =>
        maybeInstance match {
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
    *
    * @param maybeInstance The instance for the update if one exists. None otherwise.
    * @param applyOperation the operation that shall be applied to the instance
    * @return The [[InstanceUpdateEffect]] that results from applying the given operation.
    */
  private[this] def updateExistingInstance(maybeInstance: Option[Instance], id: Instance.Id)(applyOperation: Instance => InstanceUpdateEffect): InstanceUpdateEffect = {
    maybeInstance match {
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
    *
    * @param maybeInstance The instance for the update if one exists. None otherwise.
    * @param applyOperation the operation that will create the instance.
    * @return The [[InstanceUpdateEffect]] that results from applying the given operation.
    */
  private[this] def createInstance(maybeInstance: Option[Instance])(applyOperation: => InstanceUpdateEffect): InstanceUpdateEffect = {
    maybeInstance match {
      case Some(instance) =>
        InstanceUpdateEffect.Failure(
          new IllegalStateException(s"${instance.instanceId} of app [${instance.runSpecId}] already exists"))

      case None =>
        applyOperation
    }
  }

}
