package mesosphere.marathon.api.v2

import javax.inject.Inject
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import javax.ws.rs._
import javax.ws.rs.core.Response.Status._
import javax.ws.rs.core.{ Context, MediaType, Response }

import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ AuthResource, MarathonMediaType }
import mesosphere.marathon.plugin.auth.{ Authorizer, Authenticator, UpdateAppOrGroup }
import mesosphere.marathon.state.GroupManager
import mesosphere.marathon.upgrade.DeploymentManager.DeploymentStepInfo
import mesosphere.marathon.upgrade.{ DeploymentAction, DeploymentPlan }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }
import mesosphere.util.Logging
import play.api.libs.json.{ JsObject, Json }

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
  def running(@Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {
    doIfAuthenticated(req, resp) { implicit identity =>
      val infos = result(service.listRunningDeployments())
        .filter(_.plan.affectedApplicationIds.exists(isAllowedToView))
        .map { currentStep => toInfo(currentStep.plan, currentStep) }
      ok(jsonString(infos))
    }
  }

  @DELETE
  @Path("{id}")
  def cancel(
    @PathParam("id") id: String,
    @DefaultValue("false")@QueryParam("force") force: Boolean,
    @Context req: HttpServletRequest, @Context resp: HttpServletResponse): Response = {

    val plan = result(service.listRunningDeployments()).find(_.plan.id == id).map(_.plan)
    plan.fold(notFound(s"DeploymentPlan $id does not exist")) { deployment =>
      doIfAuthorized(req, resp, UpdateAppOrGroup, deployment.affectedApplicationIds.toSeq: _*) { _ =>
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

  private def toInfo(
    deployment: DeploymentPlan,
    currentStepInfo: DeploymentStepInfo): JsObject = {

    val steps = deployment.steps.map(step => step.actions.map(actionToMap)).map(Json.toJson(_))
    Json.obj(
      "id" -> deployment.id,
      "version" -> deployment.version,
      "affectedApps" -> deployment.affectedApplicationIds.map(_.toString),
      "steps" -> steps,
      "currentActions" -> currentStepInfo.step.actions.map(actionToMap),
      "currentStep" -> currentStepInfo.nr,
      "totalSteps" -> deployment.steps.size
    )
  }

  def actionToMap(action: DeploymentAction): Map[String, String] =
    Map(
      "action" -> action.getClass.getSimpleName,
      "app" -> action.app.id.toString
    )
}
