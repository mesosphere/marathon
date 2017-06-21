package mesosphere.marathon
package core.deployment

import akka.Done
import akka.actor.ActorRef

import scala.concurrent.Future

/**
  * Provides the interface to start and cancel a deployment and to list currently running deployments
  * with their additional information ([[DeploymentStepInfo]])
  */
trait DeploymentManager {

  /**
    * Starts a deployment for passed deployment plan. origSender actor reference is used to send a
    * [[mesosphere.marathon.MarathonSchedulerActor.DeploymentStarted]]
    * message after deployment was persisted or a [[mesosphere.marathon.MarathonSchedulerActor.DeploymentFailed]]
    * if an error occurred. Method returns a Future that will succeed when deployment is completed or fail otherwise.
    *
    * @param plan deployment plan to start
    * @param force if true, existing conflicting deployments would be canceled prior to starting the passed deployment
    * @param origSender will receive either [[mesosphere.marathon.MarathonSchedulerActor.DeploymentStarted]] or
    *                   [[mesosphere.marathon.MarathonSchedulerActor.DeploymentFailed]] during the course of the deployment
    * @return a Future to signal deployment completion
    */
  def start(plan: DeploymentPlan, force: Boolean = false, origSender: ActorRef): Future[Done]

  /**
    * Cancels a running deployment.
    *
    * @param plan deployment to cancel
    * @return a Future that will succeed when the deployment is cancelled or fail otherwise.
    */
  def cancel(plan: DeploymentPlan): Future[Done]

  /**
    * Returns currently running deployments with their corresponding states ([[DeploymentStepInfo]]).
    *
    * @return a Future wrapping a sequence with current deployments
    */
  def list(): Future[Seq[DeploymentStepInfo]]
}
