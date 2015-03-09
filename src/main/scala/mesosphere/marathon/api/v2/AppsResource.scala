package mesosphere.marathon.api.v2

import java.net.URI
import java.util.UUID
import javax.inject.{ Inject, Named }
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import akka.event.EventStream
import com.codahale.metrics.annotation.Timed
import mesosphere.marathon.api.{ ModelValidation, RestResource }
import mesosphere.marathon.api.v2.json.EnrichedTask
import mesosphere.marathon.event.{ ApiPostEvent, EventModule }
import mesosphere.marathon.health.{ HealthCheckManager, HealthCounts }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.{ DeploymentStep, RestartApplication, DeploymentPlan }
import mesosphere.marathon.{ ConflictingChangeException, MarathonConf, MarathonSchedulerService }
import mesosphere.marathon.api.v2.json.Formats._
import play.api.libs.json.{ Writes, JsObject, Json }

import scala.collection.immutable.Seq

@Path("v2/apps")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class AppsResource @Inject() (
    @Named(EventModule.busName) eventBus: EventStream,
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    healthCheckManager: HealthCheckManager,
    taskFailureRepository: TaskFailureRepository,
    val config: MarathonConf,
    groupManager: GroupManager) extends RestResource with ModelValidation {

  import AppsResource._
  import mesosphere.util.ThreadPoolContext.context

  val ListApps = """^((?:.+/)|)\*$""".r
  val EmbedTasks = "apps.tasks"
  val EmbedTasksAndFailures = "apps.failures"

  @GET
  @Timed
  def index(@QueryParam("cmd") cmd: String,
            @QueryParam("id") id: String,
            @QueryParam("embed") embed: String): String = {
    val apps = if (cmd != null || id != null) search(cmd, id) else service.listApps()
    val runningDeployments = result(service.listRunningDeployments()).map(r => r._1)
    val mapped = embed match {
      case EmbedTasks =>
        apps.map { app =>
          val enrichedApp = app.withTasksAndDeployments(
            enrichedTasks(app),
            healthCounts(app),
            runningDeployments
          )
          WithTasksAndDeploymentsWrites.writes(enrichedApp)
        }

      case EmbedTasksAndFailures =>
        apps.map { app =>
          WithTasksAndDeploymentsAndFailuresWrites.writes(
            app.withTasksAndDeploymentsAndFailures(
              enrichedTasks(app),
              healthCounts(app),
              runningDeployments,
              taskFailureRepository.current(app.id)
            )
          )
        }

      case _ =>
        apps.map { app =>
          val enrichedApp = app.withTaskCountsAndDeployments(
            enrichedTasks(app),
            healthCounts(app),
            runningDeployments
          )
          WithTaskCountsAndDeploymentsWrites.writes(enrichedApp)
        }
    }

    Json.obj("apps" -> mapped).toString()
  }

  @POST
  @Timed
  def create(@Context req: HttpServletRequest, body: Array[Byte],
             @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val app = Json.parse(body).as[AppDefinition]
    service.getApp(app.id.copy(absolute = true)) match {
      case None =>
        val (_, managed) = create(req, app, force)
        Response
          .created(new URI(managed.id.toString))
          .entity(managed)
          .build()

      case _ =>
        Response
          .status(409)
          .entity(Json.obj("message" -> s"An app with id [${app.id}] already exists.").toString)
          .build()
    }
  }

  private def create(
    req: HttpServletRequest,
    app: AppDefinition,
    force: Boolean): (DeploymentPlan, AppDefinition.WithTasksAndDeployments) = {
    val baseId = app.id.canonicalPath()
    requireValid(checkAppConstraints(app, baseId.parent))

    val conflicts = checkAppConflicts(app, baseId, service)
    if (conflicts.nonEmpty)
      throw new ConflictingChangeException(conflicts.mkString(","))

    maybePostEvent(req, app)

    val managedApp = app.copy(
      id = baseId,
      dependencies = app.dependencies.map(_.canonicalPath(baseId))
    )

    val deploymentPlan = result(
      groupManager.updateApp(
        baseId,
        _ => managedApp,
        managedApp.version,
        force
      )
    )

    val managedAppWithDeployments = managedApp.withTasksAndDeployments(
      appTasks = Nil,
      healthCounts = HealthCounts(0, 0, 0),
      runningDeployments = Seq(deploymentPlan)
    )

    deploymentPlan -> managedAppWithDeployments
  }

  @GET
  @Path("""{id:.+}""")
  @Timed
  def show(@PathParam("id") id: String): Response = {
    def runningDeployments: Seq[DeploymentPlan] = result(service.listRunningDeployments()).map(r => r._1)
    def transitiveApps(gid: PathId): Response = {
      val apps = result(groupManager.group(gid)).map(group => group.transitiveApps).getOrElse(Nil)
      val withTasks = apps.map { app =>
        val enrichedApp = app.withTasksAndDeploymentsAndFailures(
          enrichedTasks(app),
          healthCounts(app),
          runningDeployments,
          taskFailureRepository.current(app.id)
        )

        WithTasksAndDeploymentsAndFailuresWrites.writes(enrichedApp)
      }
      ok(Json.obj("*" -> withTasks).toString())
    }
    def app(): Response = service.getApp(id.toRootPath) match {
      case Some(app) =>
        val mapped = app.withTasksAndDeploymentsAndFailures(
          enrichedTasks(app),
          healthCounts(app),
          runningDeployments,
          taskFailureRepository.current(app.id)
        )
        ok(Json.obj("app" -> WithTasksAndDeploymentsAndFailuresWrites.writes(mapped)).toString())

      case None => unknownApp(id.toRootPath)
    }
    id match {
      case ListApps(gid) => transitiveApps(gid.toRootPath)
      case _             => app()
    }
  }

  @PUT
  @Path("""{id:.+}""")
  @Timed
  def replace(@Context req: HttpServletRequest,
              @PathParam("id") id: String,
              @DefaultValue("false")@QueryParam("force") force: Boolean,
              body: Array[Byte]): Response = {
    val appUpdate = Json.parse(body).as[AppUpdate]
    // prefer the id from the AppUpdate over that in the UI
    val appId = appUpdate.id.map(_.canonicalPath()).getOrElse(id.toRootPath)
    // TODO error if they're not the same?
    val updateWithId = appUpdate.copy(id = Some(appId))

    requireValid(checkUpdate(updateWithId, needsId = false))

    service.getApp(appId) match {
      case Some(app) =>
        //if version is defined, replace with version
        val update = updateWithId.version.flatMap(v => service.getApp(appId, v)).orElse(Some(updateWithId(app)))
        val response = update.map { updatedApp =>
          maybePostEvent(req, updatedApp)
          val deployment = result(groupManager.updateApp(appId, _ => updatedApp, updatedApp.version, force))
          deploymentResult(deployment)
        }
        response.getOrElse(unknownApp(appId, updateWithId.version))

      case None =>
        val (deploymentPlan, app) = create(req, updateWithId(AppDefinition(appId)), force)
        deploymentResult(deploymentPlan, Response.created(new URI(app.id.toString)))
    }
  }

  @PUT
  @Timed
  def replaceMultiple(@DefaultValue("false")@QueryParam("force") force: Boolean, body: Array[Byte]): Response = {
    val updates = Json.parse(body).as[Seq[AppUpdate]]
    requireValid(checkUpdates(updates))
    val version = Timestamp.now()
    def updateApp(update: AppUpdate, app: AppDefinition): AppDefinition = {
      update.version.flatMap(v => service.getApp(app.id, v)).orElse(Some(update(app))).getOrElse(app)
    }
    def updateGroup(root: Group): Group = updates.foldLeft(root) { (group, update) =>
      update.id match {
        case Some(id) => group.updateApp(id.canonicalPath(), updateApp(update, _), version)
        case None     => group
      }
    }
    val deployment = result(groupManager.update(PathId.empty, updateGroup, version, force))
    deploymentResult(deployment)
  }

  @DELETE
  @Path("""{id:.+}""")
  @Timed
  def delete(@Context req: HttpServletRequest,
             @DefaultValue("true")@QueryParam("force") force: Boolean,
             @PathParam("id") id: String): Response = {
    val appId = id.toRootPath
    service.getApp(appId) match {
      case Some(app) =>
        maybePostEvent(req, AppDefinition(id = appId))
        val deployment = result(groupManager.update(appId.parent, _.removeApplication(appId), force = force))
        deploymentResult(deployment)

      case None => unknownApp(appId)
    }
  }

  @Path("{appId:.+}/tasks")
  def appTasksResource(): AppTasksResource =
    new AppTasksResource(service, taskTracker, healthCheckManager, config, groupManager)

  @Path("{appId:.+}/versions")
  def appVersionsResource(): AppVersionsResource = new AppVersionsResource(service, config)

  @POST
  @Path("{id:.+}/restart")
  def restart(@PathParam("id") id: String,
              @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val appId = id.toRootPath
    service.getApp(appId) match {
      case Some(app) =>
        val future = groupManager.group(app.id.parent).map {
          case Some(group) =>
            val newApp = app.copy(version = Timestamp.now())
            val newGroup = group.updateApp(app.id, _ => newApp, Timestamp.now())
            val plan = DeploymentPlan(
              UUID.randomUUID().toString,
              group,
              newGroup,
              DeploymentStep(RestartApplication(newApp) :: Nil) :: Nil,
              Timestamp.now())
            val res = service.deploy(plan, force = force)
            result(res)
            deploymentResult(plan)

          case None => unknownGroup(app.id.parent)
        }
        result(future)

      case None => unknownApp(appId)
    }
  }

  private def enrichedTasks(app: AppDefinition): Seq[EnrichedTask] = {
    val tasks = taskTracker.get(app.id).map { task =>
      task.getId -> task
    }.toMap

    for {
      (taskId, results) <- result(healthCheckManager.statuses(app.id)).to[Seq]
      task <- tasks.get(taskId)
    } yield EnrichedTask(app.id, task, results.map(Option(_)))
  }

  private def healthCounts(app: AppDefinition): HealthCounts = result(healthCheckManager.healthCounts(app.id))

  private def maybePostEvent(req: HttpServletRequest, app: AppDefinition) =
    eventBus.publish(ApiPostEvent(req.getRemoteAddr, req.getRequestURI, app))

  private def search(cmd: String, id: String): Iterable[AppDefinition] = {
    /* Returns true iff `a` is a prefix of `b`, case-insensitively */
    def isPrefix(a: String, b: String): Boolean =
      b.toLowerCase contains a.toLowerCase

    service.listApps().filter { app =>
      val appMatchesCmd =
        cmd != null &&
          cmd.nonEmpty &&
          app.cmd.exists(isPrefix(cmd, _))

      val appMatchesId =
        id != null &&
          id.nonEmpty && isPrefix(id, app.id.toString)

      appMatchesCmd || appMatchesId
    }
  }
}

object AppsResource {
  implicit val WithTaskCountsAndDeploymentsWrites: Writes[AppDefinition.WithTaskCountsAndDeployments] = Writes { app =>
    val appJson = AppDefinitionWrites.writes(app).as[JsObject]

    appJson ++ Json.obj(
      "tasksStaged" -> app.tasksStaged,
      "tasksRunning" -> app.tasksRunning,
      "tasksHealthy" -> app.tasksHealthy,
      "tasksUnhealthy" -> app.tasksUnhealthy,
      "deployments" -> app.deployments
    )
  }

  implicit val WithTasksAndDeploymentsWrites: Writes[AppDefinition.WithTasksAndDeployments] = Writes { app =>
    val appJson = WithTaskCountsAndDeploymentsWrites.writes(app).as[JsObject]

    appJson ++ Json.obj(
      "tasks" -> app.tasks
    )
  }

  implicit val WithTasksAndDeploymentsAndFailuresWrites: Writes[AppDefinition.WithTasksAndDeploymentsAndTaskFailures] =
    Writes { app =>
      val appJson = WithTasksAndDeploymentsWrites.writes(app).as[JsObject]

      appJson ++ Json.obj(
        "lastTaskFailure" -> app.lastTaskFailure
      )
    }
}
