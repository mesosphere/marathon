package mesosphere.marathon.api.v2

import java.util.concurrent.{ Callable, TimeUnit }
import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.Response.Status._
import javax.ws.rs.core.{ MediaType, Response }

import com.google.common.cache.{ CacheBuilder, Cache }
import mesosphere.marathon.api.{ MarathonMediaType, RestResource }
import mesosphere.marathon.state.GroupManager
import mesosphere.marathon.upgrade.DeploymentManager.DeploymentStepInfo
import mesosphere.marathon.upgrade.{ DeploymentAction, DeploymentPlan }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }
import mesosphere.util.Logging

@Path("v2/deployments")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class DeploymentsResource @Inject() (
  service: MarathonSchedulerService,
  groupManager: GroupManager,
  val config: MarathonConf)
    extends RestResource
    with Logging {

  val cache: Cache[String, Response] = CacheBuilder.newBuilder()
    .expireAfterWrite(3, TimeUnit.SECONDS)
    .build()

  @GET
  def running(): Response = {
    val key = "running"
    val valueLoader = new Callable[Response] {
      override def call(): Response = {
        log.info("Computing response")
        ok(result(service.listRunningDeployments()).map {
          case (plan, currentStep) => toInfo(plan, currentStep)
        })
      }
    }

    cache.get(key, valueLoader)
  }

  @DELETE
  @Path("{id}")
  def cancel(
    @PathParam("id") id: String,
    @DefaultValue("false")@QueryParam("force") force: Boolean): Response =
    result(service.listRunningDeployments())
      .find(_._1.id == id)
      .fold(notFound(s"DeploymentPlan $id does not exist")) {
        case (plan, _) if force =>
          // do not create a new deployment to return to the previous state
          log.info(s"Canceling deployment [$id]")
          service.cancelDeployment(id)
          status(ACCEPTED) // 202: Accepted
        case (plan, _) =>
          // create a new deployment to return to the previous state
          deploymentResult(result(groupManager.update(
            plan.original.id,
            plan.revert,
            force = true
          )))
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

  def actionToMap(action: DeploymentAction): Map[String, String] =
    Map(
      "action" -> action.getClass.getSimpleName,
      "app" -> action.app.id.toString
    )
}
