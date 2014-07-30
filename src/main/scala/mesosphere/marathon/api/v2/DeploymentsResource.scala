package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.MediaType

import mesosphere.marathon.api.RestResource
import mesosphere.marathon.state.GroupManager
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }

@Path("v2/deployments")
@Produces(Array(MediaType.APPLICATION_JSON))
class DeploymentsResource @Inject() (service: MarathonSchedulerService, groupManager: GroupManager, val config: MarathonConf) extends RestResource {

  @GET
  def running() = ok(result(service.listRunningDeployments()).map(toInfo))

  @DELETE
  @Path("{id}")
  def cancel(@PathParam("id") id: String) = {
    result(service.listRunningDeployments()).find(_.id == id).fold(notFound(s"DeploymentPlan $id does not exist")) { plan =>
      deploymentResult(result(groupManager.update(plan.original.id, _ => plan.original, force = true)))
    }
  }

  private def toInfo(deployment: DeploymentPlan) = Map(
    "id" -> deployment.id,
    "version" -> deployment.version,
    "affectedApplications" -> deployment.affectedApplicationIds.map(_.toString),
    "steps" -> deployment.steps.map(step => step.actions.map { action =>
      Map(
        "action" -> action.getClass.getSimpleName,
        "application" -> action.app.id.toString
      )
    })
  )
}
