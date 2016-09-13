package mesosphere.marathon.core.task.termination.impl

import akka.Done
import akka.actor.ActorRef
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import org.slf4j.LoggerFactory

import scala.concurrent.{ Future, Promise }

// TODO(PODS): adjust the interface to handle instances; rename etc
private[termination] class TaskKillServiceDelegate(actorRef: ActorRef) extends TaskKillService {
  import TaskKillServiceDelegate.log
  import TaskKillServiceActor._

  override def killTasks(instances: Iterable[Instance], reason: TaskKillReason): Future[Done] = {
    log.info(
      s"Killing ${instances.size} tasks for reason: $reason (ids: {} ...)",
      instances.take(3).map(_.instanceId).mkString(","))

    val promise = Promise[Done]
    // TODO(PODS): the messages should transport instances and let the killService decide what to do with them
    instances.foreach(instance => actorRef ! KillTasks(instance.tasks, promise))

    promise.future
  }

  override def killTask(task: Instance, reason: TaskKillReason): Future[Done] = {
    killTasks(Seq(task), reason)
  }

  // TODO(PODS): we might want to keep this to kill unknown tasks we receive from Mesos
  override def killUnknownTask(taskId: Task.Id, reason: TaskKillReason): Future[Done] = {
    log.info(s"Killing 1 unknown task for reason: $reason (id: {})", taskId)

    val promise = Promise[Done]
    actorRef ! KillUnknownTaskById(taskId, promise)
    promise.future
  }
}

object TaskKillServiceDelegate {
  private[impl] val log = LoggerFactory.getLogger(getClass)
}
