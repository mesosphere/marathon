package mesosphere.marathon.core.task.termination.impl

import akka.Done
import akka.actor.ActorRef
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import org.slf4j.LoggerFactory

import scala.concurrent.{ Future, Promise }
import scala.collection.immutable.Seq

// TODO(PODS): adjust the interface to handle instances; rename etc
private[termination] class TaskKillServiceDelegate(actorRef: ActorRef) extends KillService {
  import TaskKillServiceDelegate.log
  import TaskKillServiceActor._

  override def killTasks(instances: Iterable[Instance], reason: KillReason): Future[Done] = {
    log.info(
      s"Killing ${instances.size} tasks for reason: $reason (ids: {} ...)",
      instances.take(3).map(_.instanceId).mkString(","))

    val promise = Promise[Done]
    instances.foreach(instance => actorRef ! KillInstances(Seq(instance), promise))

    promise.future
  }

  override def killTask(instance: Instance, reason: KillReason): Future[Done] = {
    killTasks(Seq(instance), reason)
  }

  override def killUnknownTask(taskId: Task.Id, reason: KillReason): Future[Done] = {
    log.info(s"Killing unknown task for reason: $reason (id: {})", taskId)

    val promise = Promise[Done]
    actorRef ! KillUnknownTaskById(taskId, promise)
    promise.future
  }
}

object TaskKillServiceDelegate {
  private[impl] val log = LoggerFactory.getLogger(getClass)
}
