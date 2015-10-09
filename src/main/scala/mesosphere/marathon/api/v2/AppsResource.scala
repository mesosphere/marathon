package mesosphere.marathon.api.v2

import java.net.URI
import java.util.UUID
import java.util.concurrent.{ Callable, TimeUnit }
import javax.inject.{ Inject, Named }
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{ Context, MediaType, Response }

import akka.event.EventStream
import com.codahale.metrics.annotation.Timed
import com.google.common.cache.{ Cache, CacheBuilder }
import mesosphere.marathon.api.{ MarathonMediaType, TaskKiller, RestResource }
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.{ EnrichedTask, V2AppDefinition, V2AppUpdate }
import mesosphere.marathon.event.{ ApiPostEvent, EventModule }
import mesosphere.marathon.health.{ HealthCheckManager, HealthCounts }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.{ DeploymentPlan, DeploymentStep, RestartApplication }
import mesosphere.marathon.{ ConflictingChangeException, MarathonConf, MarathonSchedulerService, UnknownAppException }
import mesosphere.util.Logging
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.Future

@Path("v2/apps")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MarathonMediaType.PREFERRED_APPLICATION_JSON))
class AppsResource @Inject() (
    @Named(EventModule.busName) eventBus: EventStream,
    service: MarathonSchedulerService,
    taskTracker: TaskTracker,
    taskKiller: TaskKiller,
    healthCheckManager: HealthCheckManager,
    taskFailureRepository: TaskFailureRepository,
    val config: MarathonConf,
    groupManager: GroupManager) extends RestResource with Logging {

  import mesosphere.util.ThreadPoolContext.context

  val ListApps = """^((?:.+/)|)\*$""".r
  val EmbedTasks = "apps.tasks"
  val EmbedTasksAndFailures = "apps.failures"

  val cache: Cache[String, String] = CacheBuilder.newBuilder()
    .expireAfterWrite(3, TimeUnit.SECONDS)
    .build()

  @GET
  @Timed // scalastyle:off
  def index(@QueryParam("cmd") cmd: String,
            @QueryParam("id") id: String,
            @QueryParam("label") label: String,
            @QueryParam("embed") embed: String): String = {

    val valueLoader = new Callable[String] {
      override def call(): String = {
        log.info("Computing response")

        val apps = search(Option(cmd), Option(id), Option(label))
        val runningDeployments = result(service.listRunningDeployments()).map(r => r._1)
        val mapped = embed match {
          case EmbedTasks =>
            apps.map { app =>
              val enrichedApp = V2AppDefinition(app).withTasksAndDeployments(
                enrichedTasks(app),
                healthCounts(app),
                runningDeployments
              )
              WithTasksAndDeploymentsWrites.writes(enrichedApp)
            }

          case EmbedTasksAndFailures =>
            apps.map { app =>
              WithTasksAndDeploymentsAndFailuresWrites.writes(
                V2AppDefinition(app).withTasksAndDeploymentsAndFailures(
                  enrichedTasks(app),
                  healthCounts(app),
                  runningDeployments,
                  taskFailureRepository.current(app.id)
                )
              )
            }

          case _ =>
            apps.map { app =>
              val enrichedApp = V2AppDefinition(app).withTaskCountsAndDeployments(
                enrichedTasks(app),
                healthCounts(app),
                runningDeployments
              )
              WithTaskCountsAndDeploymentsWrites.writes(enrichedApp)
            }
        }

        Json.obj("apps" -> mapped).toString()
      }
    }

    val key = s"id=$id,cmd=$cmd,label=$label,embed=$embed"
    cache.get(key, valueLoader)
  }

  @POST
  @Timed
  def create(@Context req: HttpServletRequest, body: Array[Byte],
             @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {

    val app = validateApp(Json.parse(body).as[V2AppDefinition].withCanonizedIds().toAppDefinition)

    def createOrThrow(opt: Option[AppDefinition]) = opt
      .map(_ => throw new ConflictingChangeException(s"An app with id [${app.id}] already exists."))
      .getOrElse(app)

    val plan = result(groupManager.updateApp(app.id, createOrThrow, app.version, force))

    val appWithDeployments = V2AppDefinition(app).withTasksAndDeployments(
      appTasks = Nil,
      healthCounts = HealthCounts(0, 0, 0),
      runningDeployments = Seq(plan)
    )

    maybePostEvent(req, appWithDeployments)
    Response.created(new URI(app.id.toString)).entity(appWithDeployments).build()
  }

  @GET
  @Path("""{id:.+}""")
  @Timed
  def show(@PathParam("id") id: String): Response = {
    def runningDeployments: Seq[DeploymentPlan] = result(service.listRunningDeployments()).map(r => r._1)
    def transitiveApps(gid: PathId): Response = {
      val apps = result(groupManager.group(gid)).map(group => group.transitiveApps).getOrElse(Nil)
      val withTasks = apps.map { app =>
        val enrichedApp = V2AppDefinition(app).withTasksAndDeploymentsAndFailures(
          enrichedTasks(app),
          healthCounts(app),
          runningDeployments,
          taskFailureRepository.current(app.id)
        )

        WithTasksAndDeploymentsAndFailuresWrites.writes(enrichedApp)
      }
      ok(Json.obj("*" -> withTasks).toString())
    }
    def app(): Future[Response] = groupManager.app(id.toRootPath).map {
      case Some(app) =>
        val mapped = V2AppDefinition(app).withTasksAndDeploymentsAndFailures(
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
      case _             => result(app())
    }
  }

  @PUT
  @Path("""{id:.+}""")
  @Timed
  def replace(@Context req: HttpServletRequest,
              @PathParam("id") id: String,
              @DefaultValue("false")@QueryParam("force") force: Boolean,
              body: Array[Byte]): Response = {
    val appId = id.toRootPath
    val appUpdate = Json.parse(body).as[V2AppUpdate].copy(id = Some(appId))
    BeanValidation.requireValid(ModelValidation.checkUpdate(appUpdate, needsId = false))

    val newVersion = Timestamp.now()
    val plan = result(groupManager.updateApp(appId, updateOrCreate(appId, _, appUpdate, newVersion), newVersion, force))

    val response = plan.original.app(appId).map(_ => Response.ok()).getOrElse(Response.created(new URI(appId.toString)))
    maybePostEvent(req, V2AppDefinition(plan.target.app(appId).get))
    deploymentResult(plan, response)
  }

  @PUT
  @Timed
  def replaceMultiple(@DefaultValue("false")@QueryParam("force") force: Boolean, body: Array[Byte]): Response = {
    val updates = Json.parse(body).as[Seq[V2AppUpdate]].map(_.withCanonizedIds())
    BeanValidation.requireValid(ModelValidation.checkUpdates(updates))
    val version = Timestamp.now()
    def updateGroup(root: Group): Group = updates.foldLeft(root) { (group, update) =>
      update.id match {
        case Some(id) => group.updateApp(id, updateOrCreate(id, _, update, version), version)
        case None     => group
      }
    }
    deploymentResult(result(groupManager.update(PathId.empty, updateGroup, version, force)))
  }

  @DELETE
  @Path("""{id:.+}""")
  @Timed
  def delete(@Context req: HttpServletRequest,
             @DefaultValue("true")@QueryParam("force") force: Boolean,
             @PathParam("id") id: String): Response = {
    val appId = id.toRootPath

    def deleteApp(group: Group) = group.app(appId)
      .map(_ => group.removeApplication(appId))
      .getOrElse(throw new UnknownAppException(appId))

    deploymentResult(result(groupManager.update(appId.parent, deleteApp, force = force)))
  }

  @Path("{appId:.+}/tasks")
  def appTasksResource(): AppTasksResource =
    new AppTasksResource(service, taskTracker, taskKiller, healthCheckManager, config, groupManager)

  @Path("{appId:.+}/versions")
  def appVersionsResource(): AppVersionsResource = new AppVersionsResource(service, config)

  @POST
  @Path("{id:.+}/restart")
  def restart(@PathParam("id") id: String,
              @DefaultValue("false")@QueryParam("force") force: Boolean): Response = {
    val appId = id.toRootPath
    val newVersion = Timestamp.now()
    def setVersionOrThrow(opt: Option[AppDefinition]) = opt
      .map(_.copy(version = newVersion))
      .getOrElse(throw new UnknownAppException(appId))

    def restartApp(versionChange: DeploymentPlan): DeploymentPlan = {
      val newApp = versionChange.target.app(appId).get
      val plan = DeploymentPlan(
        UUID.randomUUID().toString,
        versionChange.original,
        versionChange.target,
        DeploymentStep(RestartApplication(newApp) :: Nil) :: Nil,
        Timestamp.now())
      result(service.deploy(plan, force = force))
      plan
    }
    //this will create an empty deployment, since version chances do not trigger restarts
    val versionChange = result(groupManager.updateApp(id.toRootPath, setVersionOrThrow, newVersion, force))
    //create a restart app deployment plan manually
    deploymentResult(restartApp(versionChange))
  }

  private def updateOrCreate(appId: PathId,
                             existing: Option[AppDefinition],
                             appUpdate: V2AppUpdate,
                             newVersion: Timestamp): AppDefinition = {
    def createApp() = validateApp(appUpdate(AppDefinition(appId)))
    def updateApp(current: AppDefinition) = validateApp(appUpdate(current))
    def rollback(version: Timestamp) = service.getApp(appId, version).getOrElse(throw new UnknownAppException(appId))
    def updateOrRollback(current: AppDefinition) = appUpdate.version.map(rollback).getOrElse(updateApp(current))
    existing.map(updateOrRollback).getOrElse(createApp()).copy(version = newVersion)
  }

  private def validateApp(app: AppDefinition): AppDefinition = {
    BeanValidation.requireValid(ModelValidation.checkAppConstraints(V2AppDefinition(app), app.id.parent))
    val conflicts = ModelValidation.checkAppConflicts(app, result(groupManager.rootGroup()))
    if (conflicts.nonEmpty) throw new ConflictingChangeException(conflicts.mkString(","))
    app
  }

  private def enrichedTasks(app: AppDefinition): Seq[EnrichedTask] = {
    val tasks = taskTracker.get(app.id).map { task =>
      task.getId -> task
    }.toMap

    for {
      (taskId, results) <- result(healthCheckManager.statuses(app.id)).to[Seq]
      task <- tasks.get(taskId)
    } yield EnrichedTask(app.id, task, results)
  }

  private def healthCounts(app: AppDefinition): HealthCounts = result(healthCheckManager.healthCounts(app.id))

  private def maybePostEvent(req: HttpServletRequest, app: V2AppDefinition) =
    eventBus.publish(ApiPostEvent(req.getRemoteAddr, req.getRequestURI, app.toAppDefinition))

  private[v2] def search(cmd: Option[String], id: Option[String], label: Option[String]): Iterable[AppDefinition] = {
    def containCaseInsensitive(a: String, b: String): Boolean = b.toLowerCase contains a.toLowerCase
    val selectors = label.map(new LabelSelectorParsers().parsed)

    result(groupManager.rootGroup()).transitiveApps.filter { app =>
      val appMatchesCmd = cmd.fold(true)(c => app.cmd.exists(containCaseInsensitive(c, _)))
      val appMatchesId = id.fold(true)(s => containCaseInsensitive(s, app.id.toString))
      val appMatchesLabel = selectors.fold(true)(_.matches(app))
      appMatchesCmd && appMatchesId && appMatchesLabel
    }
  }
}
