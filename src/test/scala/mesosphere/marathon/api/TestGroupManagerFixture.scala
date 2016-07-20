package mesosphere.marathon.api

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.group.{ GroupManager, GroupManagerModule }
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppRepository, GroupRepository }
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import mesosphere.marathon.{ AllConf, DeploymentService, MarathonConf, MarathonSchedulerService }
import mesosphere.util.{ CapConcurrentExecutions, CapConcurrentExecutionsMetrics }

class TestGroupManagerFixture extends Mockito with MarathonActorSupport {
  val service = mock[MarathonSchedulerService]
  val appRepository = mock[AppRepository]
  val groupRepository = mock[GroupRepository]
  val eventBus = mock[EventStream]
  val provider = mock[StorageProvider]

  AllConf.withTestConfig(Seq("--zk_timeout", "1000"))
  val config = AllConf.config.get.asInstanceOf[MarathonConf]

  val metricRegistry = new MetricRegistry()
  val metrics = new Metrics(metricRegistry)
  val capMetrics = new CapConcurrentExecutionsMetrics(metrics, classOf[GroupManager])

  val actorId = new AtomicInteger(0)
  private[this] def serializeExecutions() = CapConcurrentExecutions(
    capMetrics,
    system,
    s"serializeGroupUpdates${actorId.incrementAndGet()}",
    maxParallel = 1,
    maxQueued = 10
  )

  groupRepository.zkRootName returns GroupRepository.zkRootName

  val schedulerProvider = new Provider[DeploymentService] {
    override def get() = service
  }

  private[this] val groupManagerModule = new GroupManagerModule(
    config = config,
    AlwaysElectedLeadershipModule.forActorSystem(system),
    serializeUpdates = serializeExecutions(),
    scheduler = schedulerProvider,
    groupRepo = groupRepository,
    appRepo = appRepository,
    storage = provider,
    eventBus = eventBus,
    metrics = metrics)

  val groupManager = groupManagerModule.groupManager
}
