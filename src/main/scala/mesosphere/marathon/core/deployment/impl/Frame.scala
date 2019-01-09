package mesosphere.marathon
package core.deployment.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.RescheduleReserved
import mesosphere.marathon.state.RunSpec

/**
  * A frame holds the state of an application, ie all its instances, health and readiness status. All orchestration logic
  * operates on a frame.
  *
  * @param instances All instances of one app.
  * @param instancesHealth The health of each instance.
  * @param instancesReady The readiness of each instance.
  * @param operations The pending operations that are used to replicate the frame state to the scheduler.
  */
case class Frame(
    instances: Map[Instance.Id, Instance],
    instancesHealth: Map[Instance.Id, Boolean],
    instancesReady: Map[Instance.Id, Boolean],
    operations: Vector[InstanceUpdateOperation] = Vector.empty) extends StrictLogging {

  /**
    * Update the health of an instance.
    *
    * @param instanceId The id of the instance which health changed.
    * @param health The new health.
    * @return an updated copy of this frame.
    */
  def updateHealth(instanceId: Instance.Id, health: Boolean): Frame = copy(instancesHealth = instancesHealth.updated(instanceId, health))

  /**
    * Update the readiness of an instance.
    *
    * @param instanceId The if of the instance which readiness changed.
    * @param ready The new readiness status.
    * @return an updated copy of this frame.
    */
  def updateReadiness(instanceId: Instance.Id, ready: Boolean): Frame = copy(instancesReady = instancesReady.updated(instanceId, ready))

  /**
    * Change the goal of an instance.
    *
    * @param instanceId The id of the instance which goal changed.
    * @param goal The new goal.
    * @return an updated copy of the instance.
    */
  def setGoal(instanceId: Instance.Id, goal: Goal): Frame = {
    val op = InstanceUpdateOperation.ChangeGoal(instanceId, goal)
    val updatedState = instances(instanceId).state.copy(goal = goal)
    val updatedInstance = instances(instanceId).copy(state = updatedState)
    copy(operations = operations :+ op, instances = instances.updated(instanceId, updatedInstance))
  }

  /**
    * Schedule new instance for the given run spec. If the instances have reservations and are terminal they will be
    * rescheduled.
    *
    * @param runSpec The new run spec version.
    * @param count The number of instances to schedule.
    * @return an updated copy of this frame.
    */
  def add(runSpec: RunSpec, count: Int): Frame = {
    val existingReservedStoppedInstances = instances.valuesIterator
      .filter(i => i.hasReservation && i.state.condition.isTerminal && i.state.goal == Goal.Stopped) // resident to relaunch
      .take(count)
      .toVector
    val rescheduleOperations = existingReservedStoppedInstances.map { instance => RescheduleReserved(instance.instanceId, runSpec) }

    // Schedule additional resident instances or all ephemeral instances
    val instancesToSchedule = existingReservedStoppedInstances.length.until(count).map { _ => Instance.scheduled(runSpec, Instance.Id.forRunSpec(runSpec.id)) }
    val scheduleOperations = instancesToSchedule.map { instance => InstanceUpdateOperation.Schedule(instance) }

    logger.info(s"Scheduling ${instancesToSchedule.length} new instances (first five: ${instancesToSchedule.take(5)} ) " +
      s"and rescheduling (${existingReservedStoppedInstances.length}) reserved instances for ${runSpec.id}:${runSpec.version}")

    // Updated internal state with scheduled and rescheduled instances
    val updatedInstances = existingReservedStoppedInstances.foldLeft(instances) {
      case (acc, instance) =>
        val updatedState = instance.state.copy(goal = Goal.Running)
        acc.updated(instance.instanceId, instance.copy(state = updatedState, runSpec = runSpec))
    } ++ instancesToSchedule.map(i => i.instanceId -> i)

    // Update operations
    val updatedOperations = operations ++ rescheduleOperations ++ scheduleOperations
    copy(operations = updatedOperations, instances = updatedInstances)
  }

  /**
    * @return a copy without pending operations.
    */
  def withoutOperations(): Frame = copy(operations = Vector.empty)

  /**
    * Adds or overrides given instance.
    *
    * Note: This method does not register update operations. Use it with care!
    *
    * @param instance The instance that should be added.
    * @return a copy with the new instance.
    */
  def withInstance(instance: Instance): Frame = {
    val updatedHealth = instancesHealth.updated(instance.instanceId, instance.state.healthy.getOrElse(false))
    copy(instances.updated(instance.instanceId, instance), instancesHealth = updatedHealth)
  }
}

object Frame {
  def apply(instances: Map[Instance.Id, Instance]): Frame = {
    val instancesHealth: Map[Instance.Id, Boolean] = instances.collect {
      case (id, instance) => id -> instance.state.healthy.getOrElse(false)
    }(collection.breakOut)
    val instancesReady: Map[Instance.Id, Boolean] = Map.empty
    new Frame(instances, instancesHealth, instancesReady)
  }

  def apply(instances: Seq[Instance]): Frame = apply(instances: _*)
  def apply(instances: Instance*): Frame = apply(instances.map(i => i.instanceId -> i).toMap)
}
