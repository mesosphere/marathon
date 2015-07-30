package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ Actor, ActorLogging, Cancellable, Props }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.mesos.protos.TaskID

import scala.concurrent.duration._

private[tracker] object KillOverdueStagedTasksActor {
  def props(taskTracker: TaskTracker, driverHolder: MarathonSchedulerDriverHolder): Props = {
    Props(new KillOverdueStagedTasksActor(taskTracker, driverHolder))
  }

  private[tracker] case object Check
}

private class KillOverdueStagedTasksActor(taskTracker: TaskTracker, driverHolder: MarathonSchedulerDriverHolder)
    extends Actor with ActorLogging {
  var checkTicker: Cancellable = _

  override def preStart(): Unit = {
    import context.dispatcher
    checkTicker = context.system.scheduler.schedule(30.seconds, 5.seconds, self, KillOverdueStagedTasksActor.Check)
  }

  override def postStop(): Unit = {
    checkTicker.cancel()
  }

  override def receive: Receive = {
    case KillOverdueStagedTasksActor.Check =>
      log.debug("checking for overdue tasks")
      driverHolder.driver.foreach { driver =>
        taskTracker.checkStagedTasks.foreach { overdueTask =>
          import mesosphere.mesos.protos.Implicits._
          log.warning("Killing overdue task '{}'", overdueTask.getId)
          driver.killTask(TaskID(overdueTask.getId))
        }
      }
  }
}
