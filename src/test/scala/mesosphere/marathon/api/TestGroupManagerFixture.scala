package mesosphere.marathon.api

import java.util.concurrent.atomic.AtomicInteger

import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppRepository, GroupManager, GroupRepository }
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService }
import mesosphere.util.{ CapConcurrentExecutions, CapConcurrentExecutionsMetrics }

import scala.concurrent.duration._

class TestGroupManagerFixture extends Mockito with MarathonActorSupport {
  val service = mock[MarathonSchedulerService]
  val appRepository = mock[AppRepository]
  val groupRepository = mock[GroupRepository]
  val eventBus = mock[EventStream]
  val provider = mock[StorageProvider]
  val config = mock[MarathonConf]

  val metricRegistry = new MetricRegistry()
  val metrics = new Metrics(metricRegistry)
  val capMetrics = new CapConcurrentExecutionsMetrics(metrics, classOf[GroupManager])

  val actorId = new AtomicInteger(0)
  def serializeExecutions() = CapConcurrentExecutions(
    capMetrics,
    system,
    s"serializeGroupUpdates${actorId.incrementAndGet()}",
    maxParallel = 1,
    maxQueued = 10
  )

  config.zkTimeoutDuration returns 1.seconds
  groupRepository.zkRootName returns GroupRepository.zkRootName

  val groupManager = new GroupManager(
    serializeUpdates = serializeExecutions(), scheduler = service,
    groupRepo = groupRepository, appRepo = appRepository,
    storage = provider, config = config, eventBus = eventBus
  )
}
