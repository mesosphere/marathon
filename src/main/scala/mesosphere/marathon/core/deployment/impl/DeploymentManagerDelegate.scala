package mesosphere.marathon
package core.deployment.impl

import akka.Done
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.deployment.impl.DeploymentManagerActor.{ CancelDeployment, ListRunningDeployments, StartDeployment }
import mesosphere.marathon.core.deployment.{ DeploymentConfig, DeploymentManager, DeploymentPlan, DeploymentStepInfo }

import scala.concurrent.Future
import scala.util.control.NonFatal

class DeploymentManagerDelegate(
    config: DeploymentConfig,
    deploymentManagerActor: ActorRef) extends DeploymentManager with StrictLogging {

  val requestTimeout: Timeout = config.deploymentManagerRequestDuration

  override def start(plan: DeploymentPlan, force: Boolean, origSender: ActorRef): Future[Done] =
    askActorFuture[StartDeployment, Done]("start")(StartDeployment(plan, origSender, force))

  override def cancel(plan: DeploymentPlan): Future[Done] =
    askActorFuture[CancelDeployment, Done]("cancel")(CancelDeployment(plan))

  override def list(): Future[Seq[DeploymentStepInfo]] =
    askActorFuture[ListRunningDeployments.type, Seq[DeploymentStepInfo]]("list")(ListRunningDeployments)

  private[this] def askActorFuture[T, R](
    method: String,
    timeout: Timeout = requestTimeout)(message: T): Future[R] = {

    implicit val timeoutImplicit: Timeout = timeout
    val answerFuture = (deploymentManagerActor ? message).mapTo[Future[R]]

    import mesosphere.marathon.core.async.ExecutionContexts.global
    answerFuture.recover {
      case NonFatal(e) => throw new RuntimeException(s"in $method", e)
    }
    answerFuture.flatMap(identity)
  }
}
