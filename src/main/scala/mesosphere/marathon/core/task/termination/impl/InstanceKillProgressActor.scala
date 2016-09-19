package mesosphere.marathon.core.task.termination.impl

import akka.Done
import akka.actor.{ Actor, ActorLogging, Props }
import mesosphere.marathon.KillingInstancesFailedException
import mesosphere.marathon.core.event.{ InstanceChanged, UnknownInstanceTerminated }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.Id

import scala.concurrent.Promise
import scala.collection.mutable
import scala.util.Try

/**
  * This actor watches over a given set of instanceIds and completes a promise when
  * all instances have been reported terminal (including LOST et al). It also
  * subscribes to the event bus to listen for instance change updates related
  * to the instance it watches.
  *
  * The actor will stop itself once all watched instances are terminal; if it is
  * stopped in any other way the promise will fail.
  *
  * @param ids the instanceIds that shall be watched.
  * @param promise the promise that shall be completed when all instances have been
  *                reported terminal.
  */
private[this] class InstanceKillProgressActor(
    ids: Iterable[Instance.Id], promise: Promise[Done]) extends Actor with ActorLogging {
  // TODO: if one of the watched instances is reported terminal before this actor subscribed to the event bus,
  //       it won't receive that event. should we reconcile instances after a certain amount of time?

  private[this] val instanceIds = mutable.HashSet[Instance.Id](ids.toVector: _*)
  // this should be used for logging to prevent polluting the logs
  private[this] val name = "InstanceKillProgressActor" + self.hashCode()

  override def preStart(): Unit = {
    if (instanceIds.nonEmpty) {
      context.system.eventStream.subscribe(self, classOf[InstanceChanged])
      context.system.eventStream.subscribe(self, classOf[UnknownInstanceTerminated])
      log.info("Starting {} to track kill progress of {} instances", name, instanceIds.size)
    } else {
      promise.tryComplete(Try(Done))
      log.info("premature aborting of {} - no instances to watch for", name)
      context.stop(self)
    }
  }

  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)

    if (!promise.isCompleted) {
      // we don't expect to be stopped without all instances being killed, so the promise should fail:
      val msg = s"$name was stopped before all instances are killed. Outstanding: ${instanceIds.mkString(",")}"
      log.error(msg)
      promise.tryFailure(new KillingInstancesFailedException(msg))
    }
  }

  override def receive: Receive = {
    case Terminal(event) if instanceIds.contains(event.id) =>
      handleTerminal(event.id)

    case UnknownInstanceTerminated(id, _, _) if instanceIds.contains(id) =>
      handleTerminal(id)
  }

  private[this] def handleTerminal(id: Id): Unit = {
    log.debug("Received terminal update for {}", id)
    instanceIds.remove(id)
    if (instanceIds.isEmpty) {
      log.info("All instances watched by {} are killed, completing promise", name)
      val success = promise.tryComplete(Try(Done))
      if (!success) log.error("Promise has already been completed in {}", name)
      context.stop(self)
    } else {
      log.info("{} still waiting for {} instances to be killed", name, instanceIds.size)
    }
  }

}

private[impl] object InstanceKillProgressActor {
  def props(toKill: Iterable[Instance.Id], promise: Promise[Done]): Props = {
    Props(new InstanceKillProgressActor(toKill, promise))
  }
}
