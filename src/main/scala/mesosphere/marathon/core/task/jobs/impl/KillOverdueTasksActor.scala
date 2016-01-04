package mesosphere.marathon.core.task.jobs.impl

import akka.actor._
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerDriverHolder }
import mesosphere.mesos.protos.TaskID
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
          import mesosphere.mesos.protos.Implicits._
          log.warn("Killing overdue task '{}'", overdueTask.getId)
          driver.killTask(TaskID(overdueTask.getId))
        }
      }
    }

    private[this] def determineOverdueTasks(now: Timestamp): Iterable[MarathonTask] = {

      val nowMillis = now.toDateTime.getMillis
      // stagedAt is set when the task is created by the scheduler
      val stagedExpire = nowMillis - config.taskLaunchTimeout()
      val unconfirmedExpire = nowMillis - config.taskLaunchConfirmTimeout()

      val toKill = taskTracker.list.values.flatMap(_.tasks).filter { task =>
        /*
       * One would think that !hasStagedAt would be better for querying these tasks. However, the current implementation
       * of [[MarathonTasks.makeTask]] will set stagedAt to a non-zero value close to the current system time.
       * Therefore, all tasks will return a non-zero value for stagedAt so that we cannot use that.
       *
       * If, for some reason, a task was created (sent to mesos), but we never received a [[TaskStatus]] update event,
       * the task will also be killed after reaching the configured maximum.
       */
        if (task.getStartedAt != 0) {
          false
        }
        else if (task.hasStatus &&
          task.getStatus.getState == TaskState.TASK_STAGING &&
          task.getStagedAt < stagedExpire) {
          log.warn(s"Should kill: Task '${task.getId}' was staged ${(nowMillis - task.getStagedAt) / 1000}s" +
            s" ago and has not yet started")
          true
        }
        else if (task.getStagedAt < unconfirmedExpire) {
          log.warn(s"Should kill: Task '${task.getId}' was launched ${(nowMillis - task.getStagedAt) / 1000}s ago " +
            s"and was not confirmed yet"
          )
          true
        }
        else false
      }

      toKill.toIterable
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
