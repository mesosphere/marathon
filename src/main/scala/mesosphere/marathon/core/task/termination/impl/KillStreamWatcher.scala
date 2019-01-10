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
  private type TerminalPredicate = (Instance, Set[Task.Id]) => Boolean

  private[impl] val whenTerminalOrReplaced: TerminalPredicate = (instance: Instance, taskIds: Set[Task.Id]) =>
    considerTerminal(instance.state.condition) || instance.tasksMap.values.forall(t => !taskIds(t.taskId))

  private[impl] val whenTerminalAndDecommissioned: TerminalPredicate = (instance: Instance, _: Set[Task.Id]) =>
    instance.state.goal == Goal.Decommissioned && considerTerminal(instance.state.condition)

  private[impl] def emitPendingTerminal(instanceUpdates: InstanceTracker.InstanceUpdates, instances: Iterable[Instance], predicate: TerminalPredicate): Source[Set[Instance.Id], NotUsed] = {

    val instancesMap: Map[Instance.Id, Set[Task.Id]] =
      instances.map { i => i.instanceId -> i.tasksMap.values.map(_.taskId).toSet }(collection.breakOut)

    instanceUpdates
      .flatMapConcat {
        case (snapshot, updates) =>
          val pendingInstanceIds: Set[Instance.Id] = snapshot.instances.iterator
            .filter { i => instancesMap.contains(i.instanceId) }
            .filterNot { i => predicate(i, instancesMap(i.instanceId)) }
            .map(_.instanceId)
            .to[Set]

          updates
            .filter { change => instancesMap.contains(change.id) }
            .scan(pendingInstanceIds) {
              case (remaining, deleted: InstanceDeleted) =>
                remaining - deleted.id
              case (remaining, InstanceUpdated(instance, _, _)) if predicate(instance, instancesMap.getOrElse(instance.instanceId, Set.empty)) =>
                remaining - instance.instanceId
              case (remaining, _) =>
                remaining
            }
            .takeWhile(_.nonEmpty, inclusive = true)
      }
  }

  /**
    * Wait until either the tasks associated with the instances are terminal, or have been replaced with new tasks.
    */
  def watchForKilledInstances(instanceUpdates: InstanceTracker.InstanceUpdates, instances: Iterable[Instance])(implicit materializer: Materializer): Future[Done] = {
    KillStreamWatcher.
      emitPendingTerminal(instanceUpdates, instances, whenTerminalOrReplaced)
      .takeWhile { pendingTerminalInstances => pendingTerminalInstances.nonEmpty }
      .runWith(Sink.ignore)
  }

  /**
    * Wait for instances to be decommissioned and expunged from the instance tracker.
    *
    * Since goals cannot be transitioned from decommissioned to running, it is safe to call this watcher method before or after the goal update is processed.
    *
    * @param instanceUpdates InstanceTracker instanceUpdates feed
    * @param instanceIds Instance ids to wait to be expunged from the instance tracker due to decomissioning
    * @param materializer Akka stream materializer
    * @return
    */
  def watchForDecommissionedInstances(instanceUpdates: InstanceTracker.InstanceUpdates, instances: Iterable[Instance])(implicit materializer: Materializer): Future[Done] = {
    KillStreamWatcher.
      emitPendingTerminal(instanceUpdates, instances, whenTerminalAndDecommissioned)
      .takeWhile { pendingTerminalInstances => pendingTerminalInstances.nonEmpty }
      .runWith(Sink.ignore)
  }
}
