package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ Actor, ActorLogging, Cancellable, Props }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.mesos.protos.TaskID

import scala.concurrent.duration._

private[tracker] object KillOverdueTasksActor {
  def props(taskTracker: TaskTracker, driverHolder: MarathonSchedulerDriverHolder, clock: Clock): Props = {
    Props(new KillOverdueTasksActor(taskTracker, driverHolder, clock))
  }

  private[tracker] case object Check
}

private class KillOverdueTasksActor(taskTracker: TaskTracker,
                                    driverHolder: MarathonSchedulerDriverHolder,
                                    clock: Clock)
    extends Actor with ActorLogging {
  var checkTicker: Cancellable = _

  override def preStart(): Unit = {
    import context.dispatcher
    checkTicker = context.system.scheduler.schedule(30.seconds, 5.seconds, self, KillOverdueTasksActor.Check)
  }

  override def postStop(): Unit = {
    checkTicker.cancel()
  }

  override def receive: Receive = {
    case KillOverdueTasksActor.Check =>
      val now = clock.now()
      log.debug("checking for overdue tasks")
      driverHolder.driver.foreach { driver =>
        taskTracker.determineOverdueTasks(now).foreach { overdueTask =>
          import mesosphere.mesos.protos.Implicits._
          log.warning("Killing overdue task '{}'", overdueTask.getId)
          driver.killTask(TaskID(overdueTask.getId))
        }
      }
  }
}
