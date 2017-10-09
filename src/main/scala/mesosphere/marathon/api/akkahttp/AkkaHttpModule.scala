package mesosphere.marathon
package api.akkahttp

import java.time.Clock

import akka.actor.ActorSystem
import akka.event.EventStream
import akka.stream.Materializer
import com.google.inject.AbstractModule
import com.google.inject.{ Provides, Scopes, Singleton }
import com.typesafe.config.Config
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.{ MarathonHttpService, TaskKiller }
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.api.akkahttp.v2.{ AppsController, EventsController, InfoController, PluginsController }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.http.HttpRequestHandler
import mesosphere.marathon.storage.StorageModule
import mesosphere.util.state.MesosLeaderInfo

class AkkaHttpModule(conf: MarathonConf with HttpConf) extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[MarathonHttpService]).to(classOf[AkkaHttpMarathonService]).in(Scopes.SINGLETON)
  }

  @Provides
  @Singleton
  @SuppressWarnings(Array("MaxParameters"))
  def provideAkkaHttpMarathonService(
    clock: Clock,
    config: Config,
    eventBus: EventStream,
    appInfoService: AppInfoService,
    groupManager: GroupManager,
    pluginManager: PluginManager,
    marathonSchedulerService: MarathonSchedulerService,
    storageModule: StorageModule,
    mesosLeaderInfo: MesosLeaderInfo,
    appTasksRes: mesosphere.marathon.api.v2.AppTasksResource)(implicit
    actorSystem: ActorSystem,
    materializer: Materializer,
    authenticator: Authenticator,
    authorizer: Authorizer,
    electionService: ElectionService,
    healthCheckManager: HealthCheckManager,
    instanceTracker: InstanceTracker,
    taskKiller: TaskKiller): AkkaHttpMarathonService = {

    import actorSystem.dispatcher
    val appsController = new AppsController(
      clock = clock,
      eventBus = eventBus,
      marathonSchedulerService = marathonSchedulerService,
      appInfoService = appInfoService,
      healthCheckManager = healthCheckManager,
      instanceTracker = instanceTracker,
      taskKiller = taskKiller,
      config = conf,
      groupManager = groupManager,
      pluginManager = pluginManager)

    val resourceController = new ResourceController
    val systemController = new SystemController(config)
    val eventsController = new EventsController(conf, eventBus)
    val infoController = InfoController(mesosLeaderInfo, storageModule.frameworkIdRepository, conf)
    val pluginsController = new PluginsController(pluginManager.plugins[HttpRequestHandler], pluginManager.definitions)
    val v2Controller = new V2Controller(appsController, eventsController, pluginsController, infoController)

    new AkkaHttpMarathonService(
      conf,
      resourceController,
      systemController,
      v2Controller)
  }
}
