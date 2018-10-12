package mesosphere.marathon
package core.task.jobs.impl

import java.time.Clock

import akka.actor._
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.state.Timestamp
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

private[jobs] object OverdueInstancesActor {
  def props(
    config: MarathonConf,
    taskTracker: InstanceTracker,
    taskStateOpProcessor: TaskStateOpProcessor,
    killService: KillService,
    clock: Clock): Props = {
    Props(new OverdueInstancesActor(new Support(config, taskTracker, taskStateOpProcessor, killService, clock)))
  }

  /**
    * Contains the core logic for the KillOverdueTasksActor.
    */
  private class Support(
      config: MarathonConf,
      taskTracker: InstanceTracker,
      taskStateOpProcessor: TaskStateOpProcessor,
      killService: KillService,
      clock: Clock) extends StrictLogging {
    import mesosphere.marathon.core.async.ExecutionContexts.global

    def check(): Future[Unit] = {
      val now = clock.now()
      logger.debug("Checking for overdue tasks")
      taskTracker.instancesBySpec().flatMap { tasksByApp =>
        val instances = tasksByApp.allInstances

        killOverdueInstances(now, instances)

        timeoutOverdueReservations(now, instances)
      }
    }

    private[this] def killOverdueInstances(now: Timestamp, instances: Seq[Instance]): Unit = {
      val instancesToKill = overdueInstances(now, instances)
      logger.info(s"Killing overdue instances: ${instancesToKill.map(_.instanceId).mkString(", ")}")
      killService.killInstancesAndForget(instancesToKill, KillReason.Overdue)
    }

    private[this] def overdueInstances(now: Timestamp, instances: Seq[Instance]): Seq[Instance] = {
      // stagedAt is set when the task is created by the scheduler
      val stagedExpire = now - config.taskLaunchTimeout().millis
      val unconfirmedExpire = now - config.taskLaunchConfirmTimeout().millis

      def launchedAndExpired(task: Task): Boolean = {
        task.status.condition match {
          case Condition.Created | Condition.Starting if task.status.stagedAt < unconfirmedExpire =>
            logger.warn(s"Should kill: ${task.taskId} was launched " +
              s"${task.status.stagedAt.until(now).toSeconds}s ago and was not confirmed yet")
            true

          case Condition.Staging if task.status.stagedAt < stagedExpire =>
            logger.warn(s"Should kill: ${task.taskId} was staged ${task.status.stagedAt.until(now).toSeconds}s" +
              " ago and has not yet started")
            true

          case _ =>
            // running
            false
        }
      }

      // TODO(PODS): adjust this to consider instance.status and `since`
      instances.filter(instance => instance.tasksMap.valuesIterator.exists(launchedAndExpired))
    }

    private[this] def timeoutOverdueReservations(now: Timestamp, instances: Seq[Instance]): Future[Unit] = {
      val taskTimeoutResults = overdueReservations(now, instances).map { instance =>
        logger.warn("Scheduling ReservationTimeout for {}", instance.instanceId)
        taskStateOpProcessor.process(InstanceUpdateOperation.ReservationTimeout(instance.instanceId))
      }
      Future.sequence(taskTimeoutResults).map(_ => ())
    }

    private[this] def overdueReservations(now: Timestamp, instances: Seq[Instance]): Seq[Instance] = {
      // TODO PODs is an Instance overdue if a single task is overdue? / move reservation to instance level
      instances.filter { instance =>
        Task.reservedTasks(instance.tasksMap.values).exists { (task: Task.Reserved) =>
          task.reservation.state.timeout.exists(_.deadline <= now)
        }
      }
    }
  }

  private[jobs] case class Check(maybeAck: Option[ActorRef])
  private[jobs] val overdueTaskActorInitialDelay: FiniteDuration = 30.seconds
  private[jobs] val overdueTaskActorCheckInterval: FiniteDuration = 5.seconds
}

private class OverdueInstancesActor(support: OverdueInstancesActor.Support) extends Actor {
  var checkTicker: Cancellable = _
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def preStart(): Unit = {
    import context.dispatcher
    checkTicker = context.system.scheduler.schedule(
      OverdueInstancesActor.overdueTaskActorInitialDelay,
      OverdueInstancesActor.overdueTaskActorCheckInterval,
      self,
      OverdueInstancesActor.Check(maybeAck = None)
    )
  }

  override def postStop(): Unit = {
    checkTicker.cancel()
  }

  override def receive: Receive = {
    case OverdueInstancesActor.Check(maybeAck) =>
      val resultFuture = support.check()
      maybeAck match {
        case Some(ack) =>
          import akka.pattern.pipe
          import context.dispatcher
          resultFuture.pipeTo(ack)

        case None =>
          import context.dispatcher
          resultFuture.onFailure { case NonFatal(e) => log.warn("error while checking for overdue tasks", e) }
      }
  }
}
