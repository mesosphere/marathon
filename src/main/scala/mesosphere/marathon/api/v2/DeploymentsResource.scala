package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.Response.ResponseBuilder
import javax.ws.rs.core.{ Response, MediaType }

import mesosphere.marathon.api.Responses
import mesosphere.marathon.state.GroupManager
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }

import scala.concurrent.{ Await, Awaitable }

@Path("v2/deployments")
@Produces(Array(MediaType.APPLICATION_JSON))
class DeploymentsResource @Inject() (service: MarathonSchedulerService, groupManager: GroupManager, config: MarathonConf) {

  @GET
  def running() = Response.ok(result(service.listRunningDeployments()).map(toInfo)).build

  @DELETE
  @Path("{id}")
  def cancel(@PathParam("id") id: String) = {
    result(service.listRunningDeployments()).find(_.id == id).fold(Responses.notFound(s"DeploymentPlan $id does not exist")) { plan =>
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

  private def result[T](fn: Awaitable[T]): T = Await.result(fn, config.zkTimeoutDuration)
  private def deploymentResult(d: DeploymentPlan, response: ResponseBuilder = Response.ok()) = {
    response.entity(Map("version" -> d.version, "deploymentId" -> d.id)).build()
  }
}
