package mesosphere.marathon
package core.task.termination.impl

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceDeleted, InstanceUpdated}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.tracker.InstanceTracker

import scala.concurrent.Future

object KillStreamWatcher extends StrictLogging {

  private[impl] def emitPendingTerminal(instanceUpdates: InstanceTracker.InstanceUpdates, instances: Iterable[Instance]): Source[Set[Instance.Id], NotUsed] = {
    def terminalWhenKilledOrReplaced(instance: Instance, taskIds: Set[Task.Id]) =
      considerTerminal(instance.state.condition) || instance.tasksMap.values.forall(t => !taskIds(t.taskId))

    instanceUpdates
      .flatMapConcat {
        case (snapshot, updates) =>
          val initialPendingInstances: Map[Instance.Id, Set[Task.Id]] = instances.map { i => i.instanceId -> i.tasksMap.values.map(_.taskId).toSet }(collection.breakOut)
          val alreadyTerminalInstanceIds: Set[Instance.Id] = snapshot.instances.iterator
            .filter { i => initialPendingInstances.contains(i.instanceId) }
            .filter { i => terminalWhenKilledOrReplaced(i, initialPendingInstances(i.instanceId)) }
            .map(_.instanceId)
            .to[Set]

          val pendingInstances = initialPendingInstances -- alreadyTerminalInstanceIds

          updates
            .filter { change => pendingInstances.contains(change.id) }
            .scan(pendingInstances) {
              case (remaining, deleted: InstanceDeleted) =>
                remaining - deleted.id
              case (remaining, InstanceUpdated(instance, _, _)) if terminalWhenKilledOrReplaced(instance, remaining.getOrElse(instance.instanceId, Set.empty)) =>
                remaining - instance.instanceId
              case (remaining, _) =>
                remaining
            }
            .map { pending => pending.keySet }
            .takeWhile(_.nonEmpty, inclusive = true)
      }
  }

  private[impl] def emitPendingDecomissioned(instanceUpdates: InstanceTracker.InstanceUpdates, instances: Set[Instance.Id]): Source[Set[Instance.Id], NotUsed] = {
    instanceUpdates
      .flatMapConcat {
        case (snapshot, updates) =>

          val pendingInstanceIds: Set[Instance.Id] = snapshot.instances.iterator
            .filter { i => instances(i.instanceId) }
            .map(_.instanceId)
            .to[Set]

          updates
            .filter { change => pendingInstanceIds.contains(change.id) }
            .scan(pendingInstanceIds) {
              case (remaining, deleted: InstanceDeleted) =>
                remaining - deleted.id
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
      emitPendingTerminal(instanceUpdates, instances)
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
  def watchForDecomissionedInstances(instanceUpdates: InstanceTracker.InstanceUpdates, instanceIds: Set[Instance.Id])(implicit materializer: Materializer): Future[Done] = {
    KillStreamWatcher.
      emitPendingDecomissioned(instanceUpdates, instanceIds)
      .takeWhile { pendingTerminalInstances => pendingTerminalInstances.nonEmpty }
      .runWith(Sink.ignore)
  }
}
