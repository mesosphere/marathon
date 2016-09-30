package mesosphere.marathon.core.task.termination.impl

import akka.Done
import akka.actor.{ Actor, ActorLogging, Props }
import mesosphere.marathon.KillingTasksFailedException
import mesosphere.marathon.core.event.UnknownTaskTerminated
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.event.MesosStatusUpdateEvent

import scala.concurrent.Promise
import scala.collection.mutable
import scala.util.Try

/**
  * This actor watches over a given set of taskIds and completes a promise when
  * all tasks have been reported terminal (including LOST et al). It also
  * subscribes to the event bus to listen for interesting mesos task status
  * updates related to the tasks it watches.
  *
  * The actor will stop itself once all watched tasks are terminal; if it is
  * stopped in any other way the promise will fail.
  *
  * @param ids the taskIds that shall be watched.
  * @param promise the promise that shall be completed when all tasks have been
  *                reported terminal.
  */
private[this] class TaskKillProgressActor(
    ids: Iterable[Task.Id], promise: Promise[Done]) extends Actor with ActorLogging {
  // TODO: if one of the watched task is reported terminal before this actor subscribed to the event bus,
  //       it won't receive that event. should we reconcile tasks after a certain amount of time?

  private[this] val taskIds = mutable.HashSet[Task.Id](ids.toVector: _*)
  // this should be used for logging to prevent polluting the logs
  private[this] val name = "TaskKillProgressActor" + self.hashCode()

  override def preStart(): Unit = {
    if (taskIds.nonEmpty) {
      context.system.eventStream.subscribe(self, classOf[MesosStatusUpdateEvent])
      context.system.eventStream.subscribe(self, classOf[UnknownTaskTerminated])
      log.info("Starting {} to track kill progress of {} tasks", name, taskIds.size)
    } else {
      promise.tryComplete(Try(Done))
      log.info("premature aborting of {} - no tasks to watch for", name)
      context.stop(self)
    }
  }

  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)

    if (!promise.isCompleted) {
      // we don't expect to be stopped without all tasks being killed, so the promise should fail:
      val msg = s"$name was stopped before all tasks are killed. Outstanding: ${taskIds.mkString(",")}"
      log.error(msg)
      promise.tryFailure(new KillingTasksFailedException(msg))
    }
  }

  override def receive: Receive = {
    case Terminal(event) if taskIds.contains(event.taskId) =>
      handleTerminal(event.taskId)

    case UnknownTaskTerminated(id, _, _) if taskIds.contains(id) =>
      handleTerminal(id)
  }

  private[this] def handleTerminal(id: Task.Id): Unit = {
    log.debug("Received terminal update for {}", id)
    taskIds.remove(id)
    if (taskIds.isEmpty) {
      log.info("All instances watched by {} are killed, completing promise", name)
      val success = promise.tryComplete(Try(Done))
      if (!success) log.error("Promise has already been completed in {}", name)
      context.stop(self)
    } else {
      log.info("{} still waiting for {} instances to be killed", name, taskIds.size)
    }
  }

}

private[impl] object TaskKillProgressActor {
  def props(toKill: Iterable[Task.Id], promise: Promise[Done]): Props = {
    Props(new TaskKillProgressActor(toKill, promise))
  }
}
