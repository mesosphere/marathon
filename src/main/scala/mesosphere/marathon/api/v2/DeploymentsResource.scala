package mesosphere.marathon
package api.v2

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.Response.Status._
import javax.ws.rs.core.{Context, MediaType}
import com.typesafe.scalalogging.StrictLogging
import javax.ws.rs.container.{AsyncResponse, Suspended}
import mesosphere.marathon.api.AuthResource
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.PathId

import scala.async.Async.{async, await}
import scala.concurrent.ExecutionContext

@Path("v2/deployments")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class DeploymentsResource @Inject() (
    service: MarathonSchedulerService,
    groupManager: GroupManager,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val config: MarathonConf
)(implicit val executionContext: ExecutionContext)
    extends AuthResource
    with StrictLogging {

  @GET
  def running(@Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        val infos = await(service.listRunningDeployments())
          .filter(_.plan.affectedRunSpecs.exists(isAuthorized(ViewRunSpec, _)))
        ok(Raml.toRaml(infos))
      }
    }

  @DELETE
  @Path("{id}")
  def cancel(
      @PathParam("id") id: String,
      @DefaultValue("false") @QueryParam("force") force: Boolean,
      @Context req: HttpServletRequest,
      @Suspended asyncResponse: AsyncResponse
  ): Unit =
    sendResponse(asyncResponse) {
      async {
        implicit val identity = await(authenticatedAsync(req))
        val plan = await(service.listRunningDeployments()).find(_.plan.id == id).map(_.plan)
        plan match {
          case None =>
            notFound(s"DeploymentPlan $id does not exist")
          case Some(deployment) =>
            deployment.affectedRunSpecs.foreach(checkAuthorization(UpdateRunSpec, _))

            if (force) {
              // do not create a new deployment to return to the previous state
              logger.info(s"Canceling deployment [$id]")
              service.cancelDeployment(deployment)
              status(ACCEPTED) // 202: Accepted
            } else {
              // create a new deployment to return to the previous state
              deploymentResult(
                await(
                  groupManager.updateRoot(
                    PathId.root,
                    deployment.revert,
                    force = true
                  )
                )
              )
            }
        }
      }
    }
}
