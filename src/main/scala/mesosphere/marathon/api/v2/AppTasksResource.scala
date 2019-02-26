package mesosphere.marathon
package api.v2

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.container.{AsyncResponse, Suspended}
import javax.ws.rs.core.{Context, MediaType}
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
import mesosphere.marathon.util.toRichFuture

import scala.async.Async._
import scala.concurrent.Future
import scala.util.{Failure, Success}

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
    @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      val instancesBySpec = await(instanceTracker.instancesBySpec)
      id match {
        case GroupTasks(gid) =>
          val groupPath = gid.toRootPath
          val maybeGroup = groupManager.group(groupPath)
          await(withAuthorization(ViewGroup, maybeGroup, Future.successful(unknownGroup(groupPath))) { group =>
            async {
              val tasks = await(runningTasks(group.transitiveAppIds, instancesBySpec)).toRaml
              ok(jsonObjString("tasks" -> tasks))
            }
          })
        case _ =>
          val appId = id.toRootPath
          val maybeApp = groupManager.app(appId)
          val tasks = await(runningTasks(Set(appId), instancesBySpec)).toRaml
          withAuthorization(ViewRunSpec, maybeApp, unknownApp(appId)) { _ =>
            ok(jsonObjString("tasks" -> tasks))
          }
      }
    }
  }

  def runningTasks(appIds: Iterable[PathId], instancesBySpec: InstancesBySpec): Future[Vector[EnrichedTask]] = {
    Future.sequence(appIds.withFilter(instancesBySpec.hasSpecInstances).map { id =>
      async {
        val health = await(healthCheckManager.statuses(id))
        instancesBySpec.specInstances(id).flatMap { i =>
          EnrichedTask.fromInstance(i, healthCheckResults = health.getOrElse(i.instanceId, Nil))
        }
      }
    }).map(_.iterator.flatten.toVector)
  }

  @GET
  @Produces(Array(RestResource.TEXT_PLAIN_LOW))
  def indexTxt(
    @PathParam("appId") appId: String,
    @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      val id = appId.toRootPath
      val instancesBySpec = await(instanceTracker.instancesBySpec)
      withAuthorization(ViewRunSpec, groupManager.app(id), unknownApp(id)) { app =>
        ok(EndpointsHelper.appsToEndpointString(ListTasks(instancesBySpec, Seq(app))))
      }
    }
  }

  @DELETE
  def deleteMany(
    @PathParam("appId") appId: String,
    @QueryParam("host") host: String,
    @QueryParam("scale")@DefaultValue("false") scale: Boolean = false,
    @QueryParam("force")@DefaultValue("false") force: Boolean = false,
    @QueryParam("wipe")@DefaultValue("false") wipe: Boolean = false,
    @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      val pathId = appId.toRootPath

      def findToKill(appTasks: Seq[Instance]): Seq[Instance] = {
        Option(host).fold(appTasks) { hostname =>
          appTasks.filter(_.hostname.contains(hostname) || hostname == "*")
        }
      }

      if (scale && wipe) throw new BadRequestException("You cannot use scale and wipe at the same time.")

      if (scale) {
        val deploymentF = taskKiller.killAndScale(pathId, findToKill, force)
        deploymentResult(await(deploymentF))
      } else {
        await(taskKiller.kill(pathId, findToKill, wipe).asTry) match {
          case Success(instances) =>
            val healthStatuses = await(healthCheckManager.statuses(pathId))
            val enrichedTasks: Seq[EnrichedTask] = instances.flatMap { i =>
              EnrichedTask.singleFromInstance(i, healthCheckResults = healthStatuses.getOrElse(i.instanceId, Nil))
            }
            ok(jsonObjString("tasks" -> enrichedTasks.toRaml))
          case Failure(PathNotFoundException(appId, version)) => unknownApp(appId, version)
        }
      }
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
    @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
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
        deploymentResult(await(deploymentF))
      } else {
        await(taskKiller.kill(pathId, findToKill, wipe).asTry) match {
          case Success(instances) =>
            val healthStatuses = await(healthCheckManager.statuses(pathId))
            instances.headOption match {
              case None =>
                unknownTask(id)
              case Some(i) =>
                val killedTask = EnrichedTask.singleFromInstance(i).get
                val enrichedTask = killedTask.copy(healthCheckResults = healthStatuses.getOrElse(i.instanceId, Nil))
                ok(jsonObjString("task" -> enrichedTask.toRaml))
            }
          case Failure(PathNotFoundException(appId, version)) => unknownApp(appId, version)
        }
      }
    }
  }
}
