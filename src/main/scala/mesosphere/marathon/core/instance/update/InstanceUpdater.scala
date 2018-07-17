package mesosphere.marathon
package core.instance.update

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Instance, Reservation}
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.{LaunchEphemeral, MesosUpdate, Reserve}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.update.TaskUpdateEffect
import mesosphere.marathon.state.{Timestamp, UnreachableEnabled}

/**
  * Provides methods that apply a given [[InstanceUpdateOperation]]
  */
object InstanceUpdater extends StrictLogging {
  private[this] val eventsGenerator = InstanceChangedEventsGenerator

  private[instance] def updatedInstance(instance: Instance, updatedTask: Task, now: Timestamp): Instance = {
    val updatedTasks = instance.tasksMap.updated(updatedTask.taskId, updatedTask)

    // We need to suspend reservation on already launched reserved instances
    // to prevent reservations being destroyed/unreserved.
    val updatedReservation = if (instance.hasReservation && updatedTask.status.condition.isTerminal) {
      val suspendedState = Reservation.State.Suspended(timeout = None)
      instance.reservation.map(_.copy(state = suspendedState))
    } else {
      instance.reservation
    }

    instance.copy(
      tasksMap = updatedTasks,
      state = Instance.InstanceState(Some(instance.state), updatedTasks, now, instance.unreachableStrategy, instance.hasReservation),
      reservation = updatedReservation)
  }

  private[marathon] def launchEphemeral(op: LaunchEphemeral, now: Timestamp): InstanceUpdateEffect = {
    val events = eventsGenerator.events(op.instance, task = None, now, previousCondition = None)
    InstanceUpdateEffect.Update(op.instance, oldState = None, events)
  }

  private[marathon] def reserve(op: Reserve, now: Timestamp): InstanceUpdateEffect = {
    val events = eventsGenerator.events(op.instance, task = None, now, previousCondition = None)
    InstanceUpdateEffect.Update(op.instance, oldState = None, events)
  }

  private def shouldBeExpunged(instance: Instance): Boolean =
    instance.tasksMap.values.forall(_.isTerminal) && !instance.hasReservation && instance.state.goal != Goal.Stopped

  private[marathon] def mesosUpdate(instance: Instance, op: MesosUpdate): InstanceUpdateEffect = {
    val now = op.now
    val taskId = Task.Id(op.mesosStatus.getTaskId)
    instance.tasksMap.get(taskId).map { task =>
      val taskEffect = task.update(instance, op.condition, op.mesosStatus, now)
      taskEffect match {
        case TaskUpdateEffect.Update(updatedTask) =>
          val updated: Instance = updatedInstance(instance, updatedTask, now)
          val events = eventsGenerator.events(updated, Some(updatedTask), now, previousCondition = Some(instance.state.condition))
          // TODO(alena) expunge only tasks in decommissioned state
          if (shouldBeExpunged(updated)) {
            // all task can be terminal only if the instance doesn't have any persistent volumes
            logger.info("all tasks of {} are terminal, requesting to expunge", updated.instanceId)
            InstanceUpdateEffect.Expunge(updated, events)
          } else {
            InstanceUpdateEffect.Update(updated, oldState = Some(instance), events)
          }

        // We might still become UnreachableInactive.
        case TaskUpdateEffect.Noop if op.condition == Condition.Unreachable &&
          instance.state.condition != Condition.UnreachableInactive =>
          val updated: Instance = updatedInstance(instance, task, now)
          if (updated.state.condition == Condition.UnreachableInactive) {
            updated.unreachableStrategy match {
              case u: UnreachableEnabled =>
                logger.info(
                  s"${updated.instanceId} is updated to UnreachableInactive after being Unreachable for more than ${u.inactiveAfter.toSeconds} seconds.")
              case _ =>
                // We shouldn't get here
                logger.error(
                  s"${updated.instanceId} is updated to UnreachableInactive in spite of there being no UnreachableStrategy")

            }
            val events = eventsGenerator.events(
              updated, Some(task), now, previousCondition = Some(instance.state.condition))
            InstanceUpdateEffect.Update(updated, oldState = Some(instance), events)
          } else {
            InstanceUpdateEffect.Noop(instance.instanceId)
          }

        case TaskUpdateEffect.Noop =>
          InstanceUpdateEffect.Noop(instance.instanceId)

        case TaskUpdateEffect.Failure(cause) =>
          InstanceUpdateEffect.Failure(cause)

        case _ =>
          InstanceUpdateEffect.Failure("ForceExpunge should never be delegated to an instance")
      }
    }.getOrElse(InstanceUpdateEffect.Failure(s"$taskId not found in ${instance.instanceId}: ${instance.tasksMap.keySet}"))
  }

  private[marathon] def reservationTimeout(instance: Instance, now: Timestamp): InstanceUpdateEffect = {
    if (instance.hasReservation) {
      // TODO(cleanup): Using Killed for now; we have no specific state yet bit this must be considered Terminal
      val updatedInstance = instance.copy(
        state = instance.state.copy(condition = Condition.Killed)
      )
      val events = eventsGenerator.events(updatedInstance, task = None, now, previousCondition = Some(instance.state.condition))

      logger.debug(s"Expunge reserved ${instance.instanceId}")

      InstanceUpdateEffect.Expunge(instance, events)
    } else {
      InstanceUpdateEffect.Failure("ReservationTimeout can only be applied to a reserved instance")
    }
  }

  private[marathon] def forceExpunge(instance: Instance, now: Timestamp): InstanceUpdateEffect = {
    val updatedInstance = instance.copy(
      // TODO(cleanup): Using Killed for now; we have no specific state yet bit this must be considered Terminal
      state = instance.state.copy(condition = Condition.Killed)
    )
    val events = InstanceChangedEventsGenerator.events(
      updatedInstance, task = None, now, previousCondition = Some(instance.state.condition))

    logger.debug(s"Force expunge ${instance.instanceId}")

    InstanceUpdateEffect.Expunge(updatedInstance, events)
  }

  private[marathon] def revert(instance: Instance): InstanceUpdateEffect = {
    InstanceUpdateEffect.Update(instance, oldState = None, events = Nil)
  }
}
