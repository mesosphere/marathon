package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.MediaType

import mesosphere.marathon.api.RestResource
import mesosphere.marathon.state.GroupManager
import mesosphere.marathon.upgrade.DeploymentManager.DeploymentStepInfo
import mesosphere.marathon.upgrade.{ DeploymentAction, DeploymentPlan }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }

@Path("v2/deployments")
@Produces(Array(MediaType.APPLICATION_JSON))
class DeploymentsResource @Inject() (service: MarathonSchedulerService, groupManager: GroupManager, val config: MarathonConf) extends RestResource {

  @GET
  def running() = ok(result(service.listRunningDeployments()).map {
    case (plan, currentStep) => toInfo(plan, currentStep)
  })

  @DELETE
  @Path("{id}")
  def cancel(@PathParam("id") id: String) = {
    result(service.listRunningDeployments()).find(_._1.id == id).fold(notFound(s"DeploymentPlan $id does not exist")) {
      case (plan, _) =>
        deploymentResult(result(groupManager.update(plan.original.id, _ => plan.original, force = true)))
    }
  }

  private def toInfo(
    deployment: DeploymentPlan,
    currentStepInfo: DeploymentStepInfo): Map[String, Any] =
    Map(
      "id" -> deployment.id,
      "version" -> deployment.version,
      "affectedApps" -> deployment.affectedApplicationIds.map(_.toString),
      "steps" -> deployment.steps.map(step => step.actions.map(actionToMap)),
      "currentActions" -> currentStepInfo.step.actions.map(actionToMap),
      "currentStep" -> currentStepInfo.nr,
      "totalSteps" -> deployment.steps.size
    )

  def actionToMap(action: DeploymentAction) =
    Map(
      "action" -> action.getClass.getSimpleName,
      "apps" -> action.app.id.toString
    )
}
