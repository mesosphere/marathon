package mesosphere.marathon.health

import akka.actor.Actor
import mesosphere.marathon.{MarathonScheduler, MarathonSchedulerDriverHolder}
import mesosphere.marathon.state.PathId
import mesosphere.marathon.tasks.TaskTracker
import concurrent.duration._
import collection.mutable


class HealthCheckAppActor(appId: PathId,
                          appVersion: String,
                          healthChecks: Seq[HealthCheck],
                          taskTracker: TaskTracker,
                          marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
                          marathonScheduler: MarathonScheduler) extends Actor {

  import HealthCheckAppActor._

  type TaskId = String

  var tasksHealth: mutable.Map[TaskId, Map[HealthCheck, Health]] = mutable.Map.empty

  override def preStart() = {
    healthChecks.foreach { hc =>
      context.system.scheduler.schedule(0.seconds, hc.interval) {
        self ! StartHealthCheck(hc)
      }
    }
  }

  override def receive: Receive = {

    case UpdateTaskHealth(taskId, healthCheck, health) =>
      tasksHealth.get(taskId).foreach { hcs =>
        val result = hcs + (healthCheck -> health)
        tasksHealth.update(taskId, result)

        val wasHealthy = hcs.values.forall(_.healthy)
        val healthy = result.values.forall(_.healthy)


        //TODO perform killing the task
        //add some logic to handle the task failure
        //TODO publish event to somewhere
      }

    case StartHealthCheck(hc) =>
      context.actorOf(HealthCheckActor.props(appVersion, hc, self))
      
  }
}

object HealthCheckAppActor {

  //TODO make props

  case class StartHealthCheck(hc: HealthCheck)

  case class UpdateTaskHealth(taskId: String, healthCheck: HealthCheck, health: Health)

  case class HealthCheckFailed(taskId: String, hc: HealthCheck)

}