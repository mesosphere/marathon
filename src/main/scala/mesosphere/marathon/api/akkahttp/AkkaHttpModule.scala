package mesosphere.marathon
package api.akkahttp

import java.time.Clock

import akka.actor.ActorSystem
import akka.event.EventStream
import com.google.inject.{ AbstractModule, Provides, Scopes, Singleton }
import akka.stream.Materializer
import com.typesafe.config.Config
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.{ MarathonHttpService, TaskKiller }
import mesosphere.marathon.api.akkahttp.v2._
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth._
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
    appTasksRes: mesosphere.marathon.api.v2.AppTasksResource,
    launchQueue: LaunchQueue)(implicit
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
    val systemController = new SystemController(conf, config, electionService)
    val eventsController = new EventsController(conf.eventStreamMaxOutstandingMessages(), eventBus, electionService,
      electionService.localLeadershipEvents)
    val infoController = InfoController(mesosLeaderInfo, storageModule.frameworkIdRepository, conf)
    val pluginsController = new PluginsController(pluginManager.plugins[HttpRequestHandler], pluginManager.definitions)
    val leaderController = LeaderController(electionService, storageModule.runtimeConfigurationRepository)
    val queueController = new QueueController(clock, launchQueue, electionService)
    val tasksController = new TasksController(instanceTracker, groupManager, healthCheckManager, electionService)

    val v2Controller = new V2Controller(
      appsController,
      eventsController,
      pluginsController,
      infoController,
      leaderController,
      queueController,
      tasksController)

    new AkkaHttpMarathonService(
      conf,
      resourceController,
      systemController,
      v2Controller)
  }
}
