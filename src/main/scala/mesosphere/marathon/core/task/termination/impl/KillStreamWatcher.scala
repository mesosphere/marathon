package mesosphere.marathon
package core.task.termination.impl

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.instance.update.{InstanceDeleted, InstanceUpdated}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.tracker.InstanceTracker

import scala.concurrent.Future

object KillStreamWatcher extends StrictLogging {
  /**
    * We have two separate predicates for determining instance terminality: tasks have since been restarted, or instance
    * is decommissioned.
    */
  private type TerminalPredicate = (Instance, Set[Task.Id]) => Boolean

  /**
    * Predicate used to determine whether or not the tasks associated with an instance have been killed.
    *
    * When a process sends some kill request, it's usually in the context of killing the tasks associated with an
    * instance at some given point in time. We count this kill as successful if we see that the taskIds have changed,
    * or if the instance itself is in a state we consider terminal.
    */
  private[impl] val whenTerminalOrReplaced: TerminalPredicate = (instance: Instance, taskIds: Set[Task.Id]) =>
    considerTerminal(instance.state.condition) || instance.tasksMap.values.forall(t => !taskIds(t.taskId))

  /**
    * Predicate used to determine whether or not an instance has been decommissioned.
    *
    * When an instance has been marked as goal decommissioned, it's safe to assume that it is never coming back, since
    * it's illegal to change an instance goal from Decommissioned back to running. As such, we assume an instance is
    * decommissioned as soon as it is both marked as goal decommissioned, and the instance is considered terminal.
    *
    * Were we to only wait for the instance to become terminal, and not for the goal to also be decommissioned, then we
    * could conclude too early that an instance is decommissioned, as it is possible that some random restart could
    * precede the receipt of the update goal command.
    */
  private[impl] val whenTerminalAndDecommissioned: TerminalPredicate = (instance: Instance, _: Set[Task.Id]) =>
    instance.state.goal == Goal.Decommissioned && considerTerminal(instance.state.condition)

  /**
    * Given instance updates, and a set of instances, emit a set of instanceIds that are not yet expunged or considered
    * terminal. Excludes instances that are expunged or considered terminal in the initial snapshot.
    *
    * @param instanceUpdates InstanceTracker instance state subscription stream
    * @param instances The instances in question to watch
    * @param predicate The mechanism to judge instance terminality; are we watching for tasks to be killed, or instance to be decommissioned.
    * @return Source of instanceIds which emits a diminishing Set of pending instanceIds, and completes when that set is empty.
    */
  private[impl] def emitPendingTerminal(instanceUpdates: InstanceTracker.InstanceUpdates, instances: Iterable[Instance], predicate: TerminalPredicate): Source[Set[Instance.Id], NotUsed] = {

    val instanceTasks: Map[Instance.Id, Set[Task.Id]] =
      instances.map { i => i.instanceId -> i.tasksMap.values.map(_.taskId).toSet }(collection.breakOut)

    instanceUpdates
      .flatMapConcat {
        case (snapshot, updates) =>
          val pendingInstanceIds: Set[Instance.Id] = snapshot.instances.iterator
            .filter { i => instanceTasks.contains(i.instanceId) }
            .filterNot { i => predicate(i, instanceTasks(i.instanceId)) }
            .map(_.instanceId)
            .to[Set]

          updates
            .filter { change => instanceTasks.contains(change.id) }
            .scan(pendingInstanceIds) {
              case (remaining, deleted: InstanceDeleted) =>
                remaining - deleted.id
              case (remaining, InstanceUpdated(instance, _, _)) if predicate(instance, instanceTasks(instance.instanceId)) =>
                remaining - instance.instanceId
              case (remaining, _) =>
                remaining
            }
            .takeWhile(_.nonEmpty, inclusive = true)
      }
  }

  /**
    * Wait until either the instances are expunged, the tasks associated with the instances are terminal, or have been
    * replaced with new tasks.
    */
  def watchForKilledInstances(instanceUpdates: InstanceTracker.InstanceUpdates, instances: Iterable[Instance])(implicit materializer: Materializer): Future[Done] = {
    KillStreamWatcher.
      emitPendingTerminal(instanceUpdates, instances, whenTerminalOrReplaced)
      .runWith(Sink.ignore)
  }

  /**
    * Wait for instances to be decommissioned and expunged from the instance tracker.
    *
    * Since goals cannot be transitioned from decommissioned to running, it is safe to call this watcher method before
    * or after the goal update is processed.
    *
    * @param instanceUpdates InstanceTracker instanceUpdates feed
    * @param instanceIds Instance ids to wait to be expunged from the instance tracker due to decomissioning
    * @param materializer Akka stream materializer
    * @return
    */
  def watchForDecommissionedInstances(instanceUpdates: InstanceTracker.InstanceUpdates, instances: Iterable[Instance])(implicit materializer: Materializer): Future[Done] = {
    KillStreamWatcher.
      emitPendingTerminal(instanceUpdates, instances, whenTerminalAndDecommissioned)
      .runWith(Sink.ignore)
  }
}
