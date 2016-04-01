package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.Response.Status._
import javax.ws.rs.core.{ Context, MediaType, Response }

import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType }
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.state.GroupManager
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }
import mesosphere.util.Logging

@Path("v2/deployments")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class DeploymentsResource @Inject() (
  service: MarathonSchedulerService,
  groupManager: GroupManager,
  val authenticator: Authenticator,
  val authorizer: Authorizer,
  val config: MarathonConf)
    extends AuthResource
    with Logging {

  @GET
  def running(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val infos = result(service.listRunningDeployments())
      .filter(_.plan.affectedApplications.exists(isAuthorized(ViewApp, _)))
    ok(infos)
  }

  @DELETE
  @Path("{id}")
  def cancel(
    @PathParam("id") id: String,
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val plan = result(service.listRunningDeployments()).find(_.plan.id == id).map(_.plan)
    plan.fold(notFound(s"DeploymentPlan $id does not exist")) { deployment =>
      deployment.affectedApplications.foreach(checkAuthorization(UpdateApp, _))

      deployment match {
        case plan: DeploymentPlan if force =>
          // do not create a new deployment to return to the previous state
          log.info(s"Canceling deployment [$id]")
          service.cancelDeployment(id)
          status(ACCEPTED) // 202: Accepted
        case plan: DeploymentPlan =>
          // create a new deployment to return to the previous state
          deploymentResult(result(groupManager.update(
            plan.original.id,
            plan.revert,
            force = true
          )))
      }
    }
  }
}
