package mesosphere.marathon.core.task.termination.impl

import akka.Done
import akka.actor.ActorRef
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import org.slf4j.LoggerFactory

import scala.concurrent.{ Future, Promise }
import scala.collection.immutable.Seq

private[termination] class TaskKillServiceDelegate(actorRef: ActorRef) extends TaskKillService {
  import TaskKillServiceDelegate.log
  import TaskKillServiceActor._

  override def killTasks(tasks: Iterable[Task], reason: TaskKillReason): Future[Done] = {
    log.info(
      s"Killing ${tasks.size} tasks for reason: $reason (ids: {} ...)",
      tasks.take(3).map(_.taskId).mkString(","))

    val promise = Promise[Done]
    actorRef ! KillTasks(tasks, promise)
    promise.future
  }

  override def killTask(task: Task, reason: TaskKillReason): Future[Done] = {
    killTasks(Seq(task), reason)
  }

  override def killUnknownTask(taskId: Task.Id, reason: TaskKillReason): Unit = {
    log.info(s"Killing unknown task for reason: $reason (id: $taskId)")
    actorRef ! KillUnknownTaskById(taskId)
  }
}

object TaskKillServiceDelegate {
  private[impl] val log = LoggerFactory.getLogger(getClass)
}
