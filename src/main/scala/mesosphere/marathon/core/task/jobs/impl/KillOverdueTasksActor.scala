package mesosphere.marathon.core.task.jobs.impl

import akka.actor._
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerDriverHolder }
import org.apache.mesos.Protos.TaskState
import org.slf4j.LoggerFactory

import scala.collection.Iterable
import scala.concurrent.duration._

private[jobs] object KillOverdueTasksActor {
  def props(
    config: MarathonConf,
    taskTracker: TaskTracker, driverHolder: MarathonSchedulerDriverHolder, clock: Clock): Props = {
    Props(new KillOverdueTasksActor(new Support(config, taskTracker, driverHolder, clock)))
  }

  /**
    * Contains the core logic for the KillOverdueTasksActor.
    */
  private class Support(
      config: MarathonConf, taskTracker: TaskTracker, driverHolder: MarathonSchedulerDriverHolder, clock: Clock) {

    private[this] val log = LoggerFactory.getLogger(getClass)

    def check(): Unit = {
      val now = clock.now()
      log.debug("checking for overdue tasks")
      driverHolder.driver.foreach { driver =>
        determineOverdueTasks(now).foreach { overdueTask =>
          log.warn("Killing overdue {}", overdueTask.taskId)
          driver.killTask(overdueTask.taskId.mesosTaskId)
        }
      }
    }

    private[this] def determineOverdueTasks(now: Timestamp): Iterable[Task] = {

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

      taskTracker.tasksByAppSync.allTasks.filter(launchedAndExpired)
    }
  }

  private[jobs] case class Check(maybeAck: Option[ActorRef])
}

private class KillOverdueTasksActor(support: KillOverdueTasksActor.Support) extends Actor with ActorLogging {
  var checkTicker: Cancellable = _

  override def preStart(): Unit = {
    import context.dispatcher
    checkTicker = context.system.scheduler.schedule(
      30.seconds, 5.seconds, self,
      KillOverdueTasksActor.Check(maybeAck = None)
    )
  }

  override def postStop(): Unit = {
    checkTicker.cancel()
  }

  override def receive: Receive = {
    case KillOverdueTasksActor.Check(maybeAck) =>
      support.check()
      maybeAck.foreach(_ ! Status.Success(()))
  }
}
