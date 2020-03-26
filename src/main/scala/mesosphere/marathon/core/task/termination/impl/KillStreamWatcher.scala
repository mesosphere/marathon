package mesosphere.marathon
package core.task.termination.impl

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceDeleted, InstanceUpdated}
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.tracker.InstanceTracker

object KillStreamWatcher extends StrictLogging {
  /**
    * We have two separate predicates for determining instance terminality: tasks have since been restarted, or instance
    * is decommissioned.
    */
  private type TerminalPredicate = (Instance, Set[Task.Id]) => Boolean

  private val considerTerminalIfConditionTerminalOrTasksReplaced: TerminalPredicate = (instance: Instance, taskIds: Set[Task.Id]) =>
    considerTerminal(instance.state.condition) || instance.tasksMap.values.forall(t => !taskIds(t.taskId))

  /**
    * Predicate used to determine whether or not an instance has been decommissioned.
    *
    */
  private val considerTerminalIfConditionTerminalAndGoalDecommissioned: TerminalPredicate = (instance: Instance, _: Set[Task.Id]) =>
    instance.state.goal == Goal.Decommissioned && considerTerminal(instance.state.condition)

  /**
    * Given instance updates, and a set of instances, emit a set of instanceIds that are not yet expunged or considered
    * terminal. Excludes instances that are expunged or considered terminal in the initial snapshot.
    *
    * @param instanceUpdates InstanceTracker instance state subscription stream
    * @param instances The instances in question to watch
    * @param terminalPredicate The mechanism to judge instance terminality; are we watching for tasks to be killed, or instance to be decommissioned.
    * @return Source of instanceIds which emits a diminishing Set of pending instanceIds, and completes when that set is empty.
    */
  private def emitPendingTerminal(instanceUpdates: InstanceTracker.InstanceUpdates, instances: Iterable[Instance], terminalPredicate: TerminalPredicate): Source[Set[Instance.Id], NotUsed] = {

    val instanceTasks: Map[Instance.Id, Set[Task.Id]] =
      instances.iterator.map { i => i.instanceId -> i.tasksMap.values.map(_.taskId).toSet }.toMap

    instanceUpdates
      .flatMapConcat {
        case (snapshot, updates) =>
          val pendingInstanceIds: Set[Instance.Id] = snapshot.instances.iterator
            .filter { i => instanceTasks.contains(i.instanceId) }
            .filterNot { i => terminalPredicate(i, instanceTasks(i.instanceId)) }
            .map(_.instanceId)
            .to(Set)

          updates
            .filter { change => instanceTasks.contains(change.id) }
            .scan(pendingInstanceIds) {
              case (remaining, deleted: InstanceDeleted) =>
                remaining - deleted.id
              case (remaining, InstanceUpdated(instance, _, _)) if terminalPredicate(instance, instanceTasks(instance.instanceId)) =>
                remaining - instance.instanceId
              case (remaining, _) =>
                remaining
            }
            .takeWhile(_.nonEmpty, inclusive = true)
      }
  }

  /**
    * Returns a Source that emits a diminishing set of instances that are not yet killed. The source completes when all
    * instances are considered killed.
    *
    * When a process sends some kill request, it's usually in the context of killing the tasks associated with an
    * instance at some given point in time. We count an instance as killed if it has different taskIds than those
    * specified with the watch, or if the instance itself is in a state we consider terminal.
    *
    * @param instanceUpdates InstanceTracker instanceUpdates feed
    * @param instances Instances to wait to be considered killed
    * @return Akka stream Source as described
    *
    */
  def watchForKilledTasks(instanceUpdates: InstanceTracker.InstanceUpdates, instances: Iterable[Instance]): Source[Set[Instance.Id], NotUsed] =
    emitPendingTerminal(instanceUpdates, instances, considerTerminalIfConditionTerminalOrTasksReplaced)

  /**
    * Returns a Source that emits a diminishing set of instances that are not yet considered decommissioned. The source
    * completes when all instances are considered decommissioned.
    *
    * When an instance has been marked as goal decommissioned, it's safe to assume that it is never coming back, since
    * it's illegal to change an instance goal from Decommissioned back to running. As such, we assume an instance is
    * decommissioned as soon as it is both marked as goal decommissioned, and the instance is considered terminal.
    *
    * Were we to only wait for the instance to become terminal, and not for the goal to also be decommissioned, then we
    * could conclude too early that an instance is decommissioned, as it is possible that some random restart could
    * precede the receipt of the update goal command.
    *
    * @param instanceUpdates InstanceTracker instanceUpdates feed
    * @param instances Instances to wait to be expunged from the instance tracker due to decomissioning
    * @return Akka stream Source as described
    */
  def watchForDecommissionedInstances(instanceUpdates: InstanceTracker.InstanceUpdates, instances: Iterable[Instance]): Source[Set[Instance.Id], NotUsed] =
    emitPendingTerminal(instanceUpdates, instances, considerTerminalIfConditionTerminalAndGoalDecommissioned)
}
