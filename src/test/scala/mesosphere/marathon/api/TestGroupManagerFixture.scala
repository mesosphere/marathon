package mesosphere.marathon
package api

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.group.GroupManagerModule
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.repository.{ AppRepository, GroupRepository }
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import mesosphere.marathon.util.WorkQueue

class TestGroupManagerFixture extends Mockito with MarathonActorSupport {
  val service = mock[MarathonSchedulerService]
  val appRepository = mock[AppRepository]
  val groupRepository = mock[GroupRepository]
  val eventBus = mock[EventStream]
  val provider = mock[StorageProvider]

  val config = AllConf.withTestConfig("--zk_timeout", "1000")

  val metricRegistry = new MetricRegistry()
  val metrics = new Metrics(metricRegistry)

  val actorId = new AtomicInteger(0)

  val schedulerProvider = new Provider[DeploymentService] {
    override def get() = service
  }

  private[this] val groupManagerModule = new GroupManagerModule(
    config = config,
    AlwaysElectedLeadershipModule.forActorSystem(system),
    serializeUpdates = WorkQueue("serializeGroupUpdates", 1, 10),
    scheduler = schedulerProvider,
    groupRepo = groupRepository,
    storage = provider,
    eventBus = eventBus,
    metrics = metrics)

  val groupManager = groupManagerModule.groupManager
}
