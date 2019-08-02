package mesosphere.marathon
package core.task.termination.impl

import akka.Done
import akka.actor.ActorRef
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{KillReason, KillService}

import scala.concurrent.{Future, Promise}
import scala.collection.immutable.Seq

private[termination] class KillServiceDelegate(actorRef: ActorRef) extends KillService with StrictLogging {
  import KillServiceActor._

  override def killInstances(instances: Seq[Instance], reason: KillReason): Future[Done] = {
    logger.info(
      s"Killing ${instances.size} instances for reason: $reason (ids: {} ...)",
      instances.map(_.instanceId).mkString(","))

    val promise = Promise[Done]
    actorRef ! KillInstances(instances, promise)

    promise.future
  }

  override def killInstance(instance: Instance, reason: KillReason): Future[Done] = {
    killInstances(Seq(instance), reason)
  }

  override def killUnknownTask(taskId: Task.Id, reason: KillReason): Unit = {
    logger.info(s"Killing unknown task for reason: $reason (id: {})", taskId)
    actorRef ! KillUnknownTaskById(taskId)
  }

  override def killInstancesAndForget(instances: Seq[Instance], reason: KillReason): Unit = {
    if (instances.nonEmpty) {
      logger.info(s"Kill and forget following instances for reason $reason: ${instances.map(_.instanceId).mkString(",")}")
      actorRef ! KillInstancesAndForget(instances)
    }
  }
}
