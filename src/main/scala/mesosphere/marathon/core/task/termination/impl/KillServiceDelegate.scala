package mesosphere.marathon.core.task.termination.impl

import akka.Done
import akka.actor.ActorRef
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import org.slf4j.LoggerFactory

import scala.concurrent.{ Future, Promise }
import scala.collection.immutable.Seq

private[termination] class KillServiceDelegate(actorRef: ActorRef) extends KillService {
  import KillServiceDelegate.log
  import KillServiceActor._

  override def killInstances(instances: Iterable[Instance], reason: KillReason): Future[Done] = {
    log.info(
      s"Killing ${instances.size} tasks for reason: $reason (ids: {} ...)",
      instances.take(3).map(_.instanceId).mkString(","))

    val promise = Promise[Done]
    actorRef ! KillInstances(instances, promise)

    promise.future
  }

  override def killInstance(instance: Instance, reason: KillReason): Future[Done] = {
    killInstances(Seq(instance), reason)
  }

  override def killUnknownTask(taskId: Task.Id, reason: KillReason): Unit = {
    log.info(s"Killing unknown task for reason: $reason (id: {})", taskId)
    actorRef ! KillUnknownTaskById(taskId)
  }
}

object KillServiceDelegate {
  private[impl] val log = LoggerFactory.getLogger(getClass)
}
