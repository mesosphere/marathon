package mesosphere.marathon.core.task.jobs.impl

import akka.actor._
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskReservationTimeoutHandler }
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import org.apache.mesos.Protos.TaskState
import org.slf4j.LoggerFactory

import scala.collection.Iterable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

private[jobs] object OverdueTasksActor {
  def props(
    config: MarathonConf,
    taskTracker: InstanceTracker,
    reservationTimeoutHandler: TaskReservationTimeoutHandler,
    killService: TaskKillService,
    clock: Clock): Props = {
    Props(new OverdueTasksActor(new Support(config, taskTracker, reservationTimeoutHandler, killService, clock)))
  }

  /**
    * Contains the core logic for the KillOverdueTasksActor.
    */
  private class Support(
      config: MarathonConf,
      taskTracker: InstanceTracker,
      reservationTimeoutHandler: TaskReservationTimeoutHandler,
      killService: TaskKillService,
      clock: Clock) {
    import scala.concurrent.ExecutionContext.Implicits.global

    private[this] val log = LoggerFactory.getLogger(getClass)

    def check(): Future[Unit] = {
      val now = clock.now()
      log.debug("checking for overdue tasks")
      taskTracker.instancesBySpec().flatMap { tasksByApp =>
        val instances = tasksByApp.allInstances

        killOverdueInstances(now, instances)

        timeoutOverdueReservations(now, instances)
      }
    }

    private[this] def killOverdueInstances(now: Timestamp, instances: Iterable[Instance]): Unit = {
      overdueTasks(now, instances).foreach { overdueTask =>
        log.info("Killing overdue {}", overdueTask.instanceId)
        killService.killTask(overdueTask, TaskKillReason.Overdue)
      }
    }

    private[this] def overdueTasks(now: Timestamp, instances: Iterable[Instance]): Iterable[Instance] = {
      // stagedAt is set when the task is created by the scheduler
      val stagedExpire = now - config.taskLaunchTimeout().millis
      val unconfirmedExpire = now - config.taskLaunchConfirmTimeout().millis

      def launchedAndExpired(task: Task): Boolean = {
        task.launched.fold(false) { launched =>
          launched.status.mesosStatus.map(_.getState) match {
            case None | Some(TaskState.TASK_STARTING) if launched.status.stagedAt < unconfirmedExpire =>
              log.warn(s"Should kill: ${task.taskId} was launched " +
                s"${(launched.status.stagedAt.until(now).toSeconds)}s ago and was not confirmed yet"
              )
              true

            case Some(TaskState.TASK_STAGING) if launched.status.stagedAt < stagedExpire =>
              log.warn(s"Should kill: ${task.taskId} was staged ${(launched.status.stagedAt.until(now).toSeconds)}s" +
                s" ago and has not yet started"
              )
              true

            case _ =>
              // running
              false
          }
        }
      }

      // TODO PODs is an Instance overdue if a single task is overdue?
      instances.filter(instance => instance.tasks.exists(launchedAndExpired))
    }

    private[this] def timeoutOverdueReservations(now: Timestamp, instances: Iterable[Instance]): Future[Unit] = {
      val taskTimeoutResults = overdueReservations(now, instances).map { instance =>
        log.warn("Scheduling ReservationTimeout for {}", instance.instanceId)
        reservationTimeoutHandler.timeout(InstanceUpdateOperation.ReservationTimeout(instance.instanceId))
      }
      Future.sequence(taskTimeoutResults).map(_ => ())
    }

    private[this] def overdueReservations(now: Timestamp, instances: Iterable[Instance]): Iterable[Instance] = {
      // TODO PODs is an Instance overdue if a single task is overdue?
      instances.filter {
        instance =>
          Task.reservedTasks(instance.tasks).exists { (task: Task.Reserved) =>
            task.reservation.state.timeout.exists(_.deadline <= now)
          }
      }
    }
  }

  private[jobs] case class Check(maybeAck: Option[ActorRef])
}

private class OverdueTasksActor(support: OverdueTasksActor.Support) extends Actor with ActorLogging {
  var checkTicker: Cancellable = _

  override def preStart(): Unit = {
    import context.dispatcher
    checkTicker = context.system.scheduler.schedule(
      30.seconds, 5.seconds, self,
      OverdueTasksActor.Check(maybeAck = None)
    )
  }

  override def postStop(): Unit = {
    checkTicker.cancel()
  }

  override def receive: Receive = {
    case OverdueTasksActor.Check(maybeAck) =>
      val resultFuture = support.check()
      maybeAck match {
        case Some(ack) =>
          import akka.pattern.pipe
          import context.dispatcher
          resultFuture.pipeTo(ack)

        case None =>
          import context.dispatcher
          resultFuture.onFailure { case NonFatal(e) => log.warning("error while checking for overdue tasks", e) }
      }
  }
}
