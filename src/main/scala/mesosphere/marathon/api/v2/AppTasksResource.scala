package mesosphere.marathon
package api.v2

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType, Response}
import mesosphere.marathon.api.EndpointsHelper.ListTasks
import mesosphere.marathon.api._
import mesosphere.marathon.core.appinfo.EnrichedTask
import scala.concurrent.ExecutionContext
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.AnyToRaml
import mesosphere.marathon.raml.Task._
import mesosphere.marathon.raml.TaskConversion._
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId._

import scala.async.Async._
import scala.concurrent.Future

@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class AppTasksResource @Inject() (
    instanceTracker: InstanceTracker,
    taskKiller: TaskKiller,
    healthCheckManager: HealthCheckManager,
    val config: MarathonConf,
    groupManager: GroupManager,
    val authorizer: Authorizer,
    val authenticator: Authenticator)(implicit val executionContext: ExecutionContext) extends AuthResource {

  val GroupTasks = """^((?:.+/)|)\*$""".r

  @GET
  def indexJson(
    @PathParam("appId") id: String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val tasksResponse = async {
      val instancesBySpec = await(instanceTracker.instancesBySpec)
      id match {
        case GroupTasks(gid) =>
          val groupPath = gid.toRootPath
          val maybeGroup = groupManager.group(groupPath)
          withAuthorization(ViewGroup, maybeGroup, unknownGroup(groupPath)) { group =>
            ok(jsonObjString("tasks" -> runningTasks(group.transitiveAppIds, instancesBySpec).toRaml))
          }
        case _ =>
          val appId = id.toRootPath
          val maybeApp = groupManager.app(appId)
          withAuthorization(ViewRunSpec, maybeApp, unknownApp(appId)) { _ =>
            ok(jsonObjString("tasks" -> runningTasks(Set(appId), instancesBySpec).toRaml))
          }
      }
    }
    result(tasksResponse)
  }

  def runningTasks(appIds: Iterable[PathId], instancesBySpec: InstancesBySpec): Vector[EnrichedTask] = {
    appIds.withFilter(instancesBySpec.hasSpecInstances).flatMap { id =>
      val health = result(healthCheckManager.statuses(id))
      instancesBySpec.specInstances(id).flatMap { i =>
        EnrichedTask.fromInstance(i, healthCheckResults = health.getOrElse(i.instanceId, Nil))
      }
    }(collection.breakOut)
  }

  @GET
  @Produces(Array(RestResource.TEXT_PLAIN_LOW))
  def indexTxt(
    @PathParam("appId") appId: String,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val id = appId.toRootPath
    result(async {
      val instancesBySpec = await(instanceTracker.instancesBySpec)
      withAuthorization(ViewRunSpec, groupManager.app(id), unknownApp(id)) { app =>
        ok(EndpointsHelper.appsToEndpointString(ListTasks(instancesBySpec, Seq(app))))
      }
    })
  }

  @DELETE
  def deleteMany(
    @PathParam("appId") appId: String,
    @QueryParam("host") host: String,
    @QueryParam("scale")@DefaultValue("false") scale: Boolean = false,
    @QueryParam("force")@DefaultValue("false") force: Boolean = false,
    @QueryParam("wipe")@DefaultValue("false") wipe: Boolean = false,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val pathId = appId.toRootPath

    def findToKill(appTasks: Seq[Instance]): Seq[Instance] = {
      Option(host).fold(appTasks) { hostname =>
        appTasks.filter(_.hostname.contains(hostname) || hostname == "*")
      }
    }

    if (scale && wipe) throw new BadRequestException("You cannot use scale and wipe at the same time.")

    if (scale) {
      val deploymentF = taskKiller.killAndScale(pathId, findToKill, force)
      deploymentResult(result(deploymentF))
    } else {
      val response: Future[Response] = async {
        val instances = await(taskKiller.kill(pathId, findToKill, wipe))
        val healthStatuses = await(healthCheckManager.statuses(pathId))
        val enrichedTasks: Seq[EnrichedTask] = instances.flatMap { i =>
          EnrichedTask.singleFromInstance(i, healthCheckResults = healthStatuses.getOrElse(i.instanceId, Nil))
        }
        ok(jsonObjString("tasks" -> enrichedTasks.toRaml))
      }.recover {
        case PathNotFoundException(appId, version) => unknownApp(appId, version)
      }

      result(response)
    }
  }

  @DELETE
  @Path("{taskId}")
  def deleteOne(
    @PathParam("appId") appId: String,
    @PathParam("taskId") id: String,
    @QueryParam("scale")@DefaultValue("false") scale: Boolean = false,
    @QueryParam("force")@DefaultValue("false") force: Boolean = false,
    @QueryParam("wipe")@DefaultValue("false") wipe: Boolean = false,
    @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    val pathId = appId.toRootPath
    def findToKill(appTasks: Seq[Instance]): Seq[Instance] = {
      try {
        val instanceId = Task.Id.parse(id).instanceId
        appTasks.filter(_.instanceId == instanceId)
      } catch {
        // the id can not be translated to an instanceId
        case _: MatchError => Seq.empty
      }
    }

    if (scale && wipe) throw new BadRequestException("You cannot use scale and wipe at the same time.")

    if (scale) {
      val deploymentF = taskKiller.killAndScale(pathId, findToKill, force)
      deploymentResult(result(deploymentF))
    } else {
      val response: Future[Response] = async {
        val instances = await(taskKiller.kill(pathId, findToKill, wipe))
        val healthStatuses = await(healthCheckManager.statuses(pathId))
        instances.headOption match {
          case None =>
            unknownTask(id)
          case Some(i) =>
            val killedTask = EnrichedTask.singleFromInstance(i).get
            val enrichedTask = killedTask.copy(healthCheckResults = healthStatuses.getOrElse(i.instanceId, Nil))
            ok(jsonObjString("task" -> enrichedTask.toRaml))
        }
      }.recover {
        case PathNotFoundException(appId, version) => unknownApp(appId, version)
      }

      result(response)
    }
  }
}
