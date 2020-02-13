package mesosphere.marathon
package core.instance.update

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Instance, Reservation}
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.{LaunchEphemeral, LaunchOnReservation, MesosUpdate, Reserve}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.update.TaskUpdateEffect
import mesosphere.marathon.state.{Timestamp, UnreachableEnabled}

/**
  * Provides methods that apply a given [[InstanceUpdateOperation]]
  */
object InstanceUpdater extends StrictLogging {
  private[this] val eventsGenerator = InstanceChangedEventsGenerator

  private[instance] def applyTaskUpdate(instance: Instance, updatedTask: Task, now: Timestamp): Instance = {
    val updatedTasks = instance.tasksMap.updated(updatedTask.taskId, updatedTask)

    // If the updated task is Reserved, it means that the real task reached a Terminal state,
    // which in turn means that the task managed to get up and running, which means that
    // its persistent volume(s) had been created, and therefore they must never be destroyed/unreserved.
    val updatedReservation = if (updatedTask.status.condition == Condition.Reserved) {
      val suspendedState = Reservation.State.Suspended(timeout = None)
      instance.reservation.map(_.copy(state = suspendedState))
    } else {
      instance.reservation
    }

    instance.copy(
      tasksMap = updatedTasks,
      state = Instance.InstanceState.transitionTo(Some(instance.state), updatedTasks, now, instance.unreachableStrategy, instance.state.goal),
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

  private def isTerminalDecomissionedAndEphemeral(instance: Instance): Boolean =
    instance.tasksMap.values.forall(t => t.isTerminal) && instance.state.goal == Goal.Decommissioned && instance.reservation.isEmpty

  private[marathon] def readyToExpungeUnreachable(instance: Instance, now: Timestamp): Boolean = {
    instance.unreachableStrategy match {
      case unreachableEnabled: UnreachableEnabled =>
        instance.tasksMap.values.exists(_.isUnreachableExpired(now, unreachableEnabled.expungeAfter))
      case _ =>
        false
    }
  }

  private def shouldBeExpunged(instance: Instance): Boolean =
    instance.tasksMap.values.forall(_.isTerminal) && instance.state.goal != Goal.Stopped

  private[marathon] def instanceUpdateEffectForTask(instance: Instance, updatedTask: Task, now: Timestamp): InstanceUpdateEffect = {
    val updated: Instance = applyTaskUpdate(instance, updatedTask, now)
    if ((updated.state == instance.state) && (updated.tasksMap == instance.tasksMap)) {
      InstanceUpdateEffect.Noop(instance.instanceId)
    } else {
      val events = eventsGenerator.events(updated, Some(updatedTask), now, previousCondition = Some(instance.state.condition))
      // TODO(alena) expunge only tasks in decommissioned state
      if (shouldBeExpunged(updated)) {
        // all task can be terminal only if the instance doesn't have any persistent volumes
        logger.info("all tasks of {} are terminal, requesting to expunge", updated.instanceId)
        InstanceUpdateEffect.Expunge(updated, events)
      } else if (readyToExpungeUnreachable(updated, now)) {
        logger.info("Expunging since the task is unreachable and the expungeAfter duration is surpassed", updated.instanceId)
        InstanceUpdateEffect.Expunge(updated, events)
      } else if (isTerminalDecomissionedAndEphemeral(updated)) {
        logger.info("Requesting to expunge {}, all tasks are terminal, instance has no reservation and is not Stopped", updated.instanceId)
        InstanceUpdateEffect.Expunge(updated, events)
      } else {
        InstanceUpdateEffect.Update(updated, oldState = Some(instance), events)
      }
    }
  }

  private[marathon] def mesosUpdate(instance: Instance, op: MesosUpdate): InstanceUpdateEffect = {
    val now = op.now
    val taskId = Task.Id(op.mesosStatus.getTaskId)
    instance.tasksMap.get(taskId).map { task =>
      val taskEffect = task.update(instance, op.condition, op.mesosStatus, now)
      taskEffect match {
        case TaskUpdateEffect.Update(updatedTask) =>
          instanceUpdateEffectForTask(instance, updatedTask, now)

        // We might still become UnreachableInactive.
        case TaskUpdateEffect.Noop if op.condition == Condition.Unreachable &&
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
                      s"${update.instance.instanceId} is updated to UnreachableInactive after being Unreachable for more than ${u.inactiveAfter.toSeconds} seconds.")
                  case _ =>
                    // We shouldn't get here
                    logger.error(
                      s"${update.instance.instanceId} is updated to UnreachableInactive in spite of there being no UnreachableStrategy")

                }
              }
              update
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

  private[marathon] def launchOnReservation(instance: Instance, op: LaunchOnReservation): InstanceUpdateEffect = {
    if (instance.isReserved) {
      val currentTasks = instance.tasksMap
      val taskEffects = currentTasks.map {
        case (taskId, task) =>
          val newTaskId = op.oldToNewTaskIds.getOrElse(
            taskId,
            throw new IllegalStateException("failed to retrieve a new task ID"))
          val status = op.statuses.getOrElse(
            newTaskId,
            throw new IllegalStateException("failed to retrieve a task status"))
          val launchedTask = Task(taskId = newTaskId, runSpecVersion = op.runSpecVersion, status = status)
          TaskUpdateEffect.Update(launchedTask)
      }

      val nonUpdates = taskEffects.filter {
        case _: TaskUpdateEffect.Update => false
        case _ => true
      }

      val allUpdates = nonUpdates.isEmpty
      if (allUpdates) {
        val updatedTasks = taskEffects.collect { case TaskUpdateEffect.Update(updatedTask) => updatedTask }
        val updated = instance.copy(
          state = instance.state.copy(
            condition = Condition.Staging,
            since = op.timestamp,
            goal = Goal.Running
          ),
          tasksMap = updatedTasks.map(task => task.taskId -> task)(collection.breakOut),
          runSpecVersion = op.runSpecVersion,
          // The AgentInfo might have changed if the agent re-registered with a new ID after a reboot
          agentInfo = op.agentInfo
        )
        val events = eventsGenerator.events(updated, task = None, op.timestamp,
          previousCondition = Some(instance.state.condition))
        InstanceUpdateEffect.Update(updated, oldState = Some(instance), events)
      } else {
        InstanceUpdateEffect.Failure(s"Unexpected taskUpdateEffects $nonUpdates")
      }
    } else {
      InstanceUpdateEffect.Failure("LaunchOnReservation can only be applied to a reserved instance")
    }
  }

  private[marathon] def reservationTimeout(instance: Instance, now: Timestamp): InstanceUpdateEffect = {
    if (instance.isReserved) {
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
