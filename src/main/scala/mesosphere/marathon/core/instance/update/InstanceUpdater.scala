package mesosphere.marathon
package core.instance.update

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Instance, Reservation}
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.{MesosUpdate, Reserve}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.update.TaskUpdateEffect
import mesosphere.marathon.state.{Timestamp, UnreachableEnabled}
import org.apache.mesos.{Protos => MesosProtos}

/**
  * Provides methods that apply a given [[InstanceUpdateOperation]]
  */
object InstanceUpdater extends StrictLogging {
  private[this] val eventsGenerator = InstanceChangedEventsGenerator

  /**
    * Apply the provided task update to the instance. If the instance is resident, and the task is terminal, update the reservation to be suspended.
    *
    * @param instance
    * @param updatedTask
    * @param now
    * @return
    */
  private[instance] def applyTaskUpdate(instance: Instance, updatedTask: Task, now: Timestamp): Instance = {
    val updatedTasks = instance.tasksMap.updated(updatedTask.taskId, updatedTask)

    // We need to suspend reservation on already launched reserved instances
    // to prevent reservations being destroyed/unreserved.
    val updatedReservation =
      if (
        updatedTask.status.condition.isTerminal && instance.hasReservation && !instance.reservation
          .exists(r => r.state.isInstanceOf[Reservation.State.Suspended.type])
      ) {
        val suspendedState = Reservation.State.Suspended
        instance.reservation.map(_.copy(state = suspendedState))
      } else {
        instance.reservation
      }

    instance.copy(
      tasksMap = updatedTasks,
      state =
        Instance.InstanceState.transitionTo(Some(instance.state), updatedTasks, now, instance.unreachableStrategy, instance.state.goal),
      reservation = updatedReservation
    )
  }

  private[marathon] def reserve(instance: Instance, op: Reserve, now: Timestamp): InstanceUpdateEffect = {
    val updatedInstance = instance.reserved(op.reservation, op.agentInfo)
    val events = eventsGenerator.events(updatedInstance, task = None, now, previousState = None)
    InstanceUpdateEffect.Update(updatedInstance, oldState = None, events)
  }

  private[marathon] def unreserve(instance: Instance, now: Timestamp): InstanceUpdateEffect = {
    require(
      instance.state.condition.isTerminal && instance.state.goal == Goal.Decommissioned,
      s"Cannot unreserve non-terminal resident $instance"
    )
    val updatedInstance = instance.copy(state = instance.state.copy(condition = Condition.Killed))
    val events = eventsGenerator.events(updatedInstance, task = None, now, previousState = Some(instance.state))
    InstanceUpdateEffect.Expunge(instance, events)
  }

  private def isTerminalDecomissionedAndEphemeral(instance: Instance): Boolean =
    instance.tasksMap.values.forall(t => t.isTerminal) && instance.state.goal == Goal.Decommissioned && instance.reservation.isEmpty

  private def shouldAbandonReservation(instance: Instance): Boolean = {

    def allAreTerminal =
      instance.tasksMap.values.iterator.forall { task =>
        task.status.condition.isTerminal
      }

    def anyAreGoneByOperator =
      instance.tasksMap.values.iterator
        .flatMap(_.status.mesosStatus)
        .exists { status =>
          status.getState == MesosProtos.TaskState.TASK_GONE_BY_OPERATOR
        }

    instance.reservation.nonEmpty && anyAreGoneByOperator && allAreTerminal
  }

  private[marathon] def readyToExpungeUnreachable(instance: Instance, now: Timestamp): Boolean = {
    instance.unreachableStrategy match {
      case unreachableEnabled: UnreachableEnabled =>
        instance.tasksMap.values.exists(_.isUnreachableExpired(now, unreachableEnabled.expungeAfter))
      case _ =>
        false
    }
  }
  private[marathon] def instanceUpdateEffectForTask(instance: Instance, updatedTask: Task, now: Timestamp): InstanceUpdateEffect = {
    val updated: Instance = applyTaskUpdate(instance, updatedTask, now)
    if ((updated.state == instance.state) && (updated.tasksMap == instance.tasksMap)) {
      InstanceUpdateEffect.Noop(instance.instanceId)
    } else {
      val events = eventsGenerator.events(updated, Some(updatedTask), now, previousState = Some(instance.state))
      if (readyToExpungeUnreachable(updated, now)) {
        logger.info("Expunging since the task is unreachable and the expungeAfter duration is surpassed", updated.instanceId)
        InstanceUpdateEffect.Expunge(updated, events)
      } else if (isTerminalDecomissionedAndEphemeral(updated)) {
        logger.info("Requesting to expunge {}, all tasks are terminal, instance has no reservation and is not Stopped", updated.instanceId)
        InstanceUpdateEffect.Expunge(updated, events)
      } else if (shouldAbandonReservation(updated)) {
        val withoutReservation =
          updated.copy(agentInfo = None, reservation = None, state = updated.state.copy(condition = Condition.Scheduled))
        InstanceUpdateEffect.Update(withoutReservation, oldState = Some(instance), events)
      } else {
        InstanceUpdateEffect.Update(updated, oldState = Some(instance), events)
      }
    }
  }

  private[marathon] def mesosUpdate(instance: Instance, op: MesosUpdate): InstanceUpdateEffect = {
    val now = op.now
    val taskId = Task.Id.parse(op.mesosStatus.getTaskId)
    instance.tasksMap
      .get(taskId)
      .map { task =>
        val taskEffect = task.update(instance, op.condition, op.mesosStatus, now)
        taskEffect match {
          case TaskUpdateEffect.Update(updatedTask) =>
            instanceUpdateEffectForTask(instance, updatedTask, now)

          // We might still become UnreachableInactive.
          case TaskUpdateEffect.Noop
              if op.condition == Condition.Unreachable &&
                instance.state.condition != Condition.UnreachableInactive =>
            val u = instanceUpdateEffectForTask(instance, task, now)
            u match {
              case noop: InstanceUpdateEffect.Noop =>
                noop
              case update: InstanceUpdateEffect.Update =>
                if (update.instance.state.condition == Condition.UnreachableInactive) {
                  update.instance.unreachableStrategy match {
                    case u: UnreachableEnabled =>
                      logger.info(
                        s"${update.instance.instanceId} is updated to UnreachableInactive after being Unreachable for more than ${u.inactiveAfter.toSeconds} seconds."
                      )
                    case _ =>
                      // We shouldn't get here
                      logger.error(
                        s"${update.instance.instanceId} is updated to UnreachableInactive in spite of there being no UnreachableStrategy"
                      )

                  }
                }
                update
            }

          case TaskUpdateEffect.Noop =>
            InstanceUpdateEffect.Noop(instance.instanceId)

          case TaskUpdateEffect.Failure(cause) =>
            InstanceUpdateEffect.Failure(cause)
        }
      }
      .getOrElse(InstanceUpdateEffect.Failure(s"$taskId not found in ${instance.instanceId}: ${instance.tasksMap.keySet}"))
  }

  private[marathon] def reservationTimeout(instance: Instance, now: Timestamp): InstanceUpdateEffect = {
    if (instance.hasReservation) {
      // TODO(cleanup): Using Killed for now; we have no specific state yet bit this must be considered Terminal
      val updatedInstance = instance.copy(
        state = instance.state.copy(condition = Condition.Killed)
      )
      val events = eventsGenerator.events(updatedInstance, task = None, now, previousState = Some(instance.state))

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
    val events = eventsGenerator.events(updatedInstance, task = None, now, previousState = Some(instance.state))

    logger.debug(s"Force expunge ${instance.instanceId}")

    InstanceUpdateEffect.Expunge(updatedInstance, events)
  }

  private[marathon] def revert(instance: Instance): InstanceUpdateEffect = {
    InstanceUpdateEffect.Update(instance, oldState = None, events = Nil)
  }

  private[marathon] def changeGoal(instance: Instance, op: InstanceUpdateOperation.ChangeGoal, now: Timestamp): InstanceUpdateEffect = {
    val updatedInstance = instance.copy(state = instance.state.copy(goal = op.goal))
    val events = eventsGenerator.events(updatedInstance, task = None, now, previousState = Some(instance.state))

    if (InstanceUpdater.isTerminalDecomissionedAndEphemeral(updatedInstance)) {
      logger.info(
        s"Instance ${instance.instanceId} with current condition ${instance.state.condition} has it's goal updated from ${instance.state.goal} to ${op.goal}. Because of that instance should be expunged now."
      )
      InstanceUpdateEffect.Expunge(updatedInstance, events = events)
    } else {
      logger.info(
        s"Instance ${instance.instanceId} with current condition ${instance.state.condition} has it's goal updated from ${instance.state.goal} to ${op.goal}."
      )
      InstanceUpdateEffect.Update(updatedInstance, oldState = Some(instance), events = events)
    }
  }
}
