package mesosphere.marathon.core.group

import javax.inject.Provider

import akka.actor.ActorRef
import akka.event.EventStream
import akka.stream.Materializer
import com.codahale.metrics.Gauge
import mesosphere.marathon.core.group.impl.{ GroupManagerActor, GroupManagerDelegate }
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.{ DeploymentService, MarathonConf }
import mesosphere.util.CapConcurrentExecutions

import scala.concurrent.Await

/**
  * Provides a [[GroupManager]] implementation.
  */
class GroupManagerModule(
    config: MarathonConf,
    leadershipModule: LeadershipModule,
    serializeUpdates: CapConcurrentExecutions,
    scheduler: Provider[DeploymentService],
    groupRepo: GroupRepository,
    storage: StorageProvider,
    eventBus: EventStream,
    metrics: Metrics)(implicit mat: Materializer) {

  private[this] val groupManagerActorRef: ActorRef = {
    val props = GroupManagerActor.props(
      serializeUpdates,
      scheduler,
      groupRepo,
      storage,
      config,
      eventBus)
    leadershipModule.startWhenLeader(props, "groupManager")
  }

  val groupManager: GroupManager = {
    val groupManager = new GroupManagerDelegate(config, groupManagerActorRef)

    metrics.gauge("service.mesosphere.marathon.app.count", new Gauge[Int] {
      override def getValue: Int = {
        // Accessing rootGroup from the repository because getting it from groupManager will fail
        // on non-leader marathon instance.
        Await.result(groupRepo.root(), config.zkTimeoutDuration).transitiveApps.size
      }
    })

    metrics.gauge("service.mesosphere.marathon.group.count", new Gauge[Int] {
      override def getValue: Int = {
        // Accessing rootGroup from the repository because getting it from groupManager will fail
        // on non-leader marathon instance.
        Await.result(groupRepo.root(), config.zkTimeoutDuration).transitiveGroups.size
      }
    })

    metrics.gauge("service.mesosphere.marathon.uptime", new Gauge[Long] {
      val startedAt = System.currentTimeMillis()

      override def getValue: Long = {
        System.currentTimeMillis() - startedAt
      }
    })

    groupManager
  }
}
