package mesosphere.marathon.core.jobs

import akka.actor.ActorRef
import akka.util.Timeout
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.AppDefinition

import scala.concurrent.Future

trait JobScheduler {
  def scheduleJob(runSpec: AppDefinition): Future[Any]
  def notifyOfTaskChanged(taskChanged: TaskChanged): Future[Any]
}

/** Delegates function calls to the JobSchedulerActor */
private[jobs] class JobSchedulerDelegate(actorRef: ActorRef) extends JobScheduler {
  import akka.pattern.ask

  import scala.concurrent.duration._

  override def scheduleJob(runSpec: AppDefinition): Future[Any] = {
    implicit val timeout: Timeout = 2.seconds
    actorRef ? JobSchedulerActor.ScheduleJob(runSpec)
  }

  // FIXME (Jobs): decide whether we want to block here or immediately respond with Unit
  // All steps are wrapped in ContinueOnErrorSteps currently ...
  override def notifyOfTaskChanged(taskChanged: TaskChanged): Future[Any] = {
    implicit val timeout: Timeout = 3.seconds
    actorRef ? taskChanged
  }

}
