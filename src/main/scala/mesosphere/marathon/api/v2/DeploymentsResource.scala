package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{ Response, MediaType }

import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }

import scala.concurrent.{ Await, Awaitable }

@Path("v2/deployments")
@Produces(Array(MediaType.APPLICATION_JSON))
class DeploymentsResource @Inject() (service: MarathonSchedulerService, config: MarathonConf) {

  @GET
  def running() = Response.ok(result(service.listRunningDeployments()).map(toInfo)).build

  private def toInfo(deployment: DeploymentPlan) = Map(
    "id" -> deployment.id,
    "affectedApplications" -> deployment.affectedApplicationIds.map(_.toString),
    "steps" -> deployment.steps.map(step => step.actions.map { action =>
      Map(
        "action" -> action.getClass.getSimpleName,
        "application" -> action.app.id.toString
      )
    })
  )

  private def result[T](fn: Awaitable[T]): T = Await.result(fn, config.zkTimeoutDuration)
}
