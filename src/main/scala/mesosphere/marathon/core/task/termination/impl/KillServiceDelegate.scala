package mesosphere.marathon
package core.task.termination.impl

import akka.actor.ActorRef
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker

import scala.collection.immutable.Seq

private[termination] class KillServiceDelegate(actorRef: ActorRef, instanceTracker: InstanceTracker) extends KillService with StrictLogging {
  import KillServiceActor._

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
