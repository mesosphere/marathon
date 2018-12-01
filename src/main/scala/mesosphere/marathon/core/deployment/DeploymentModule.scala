package mesosphere.marathon
package core.deployment

import akka.actor.{ActorRef, Props}
import akka.event.EventStream
import akka.stream.Materializer
import mesosphere.marathon.core.deployment.impl.{DeploymentActor, DeploymentManagerActor, DeploymentManagerDelegate}
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.repository.DeploymentRepository

/**
  * Provides a [[DeploymentManager]] implementation that can be used to start and cancel a deployment and
  * to list currently running deployments.
  */
class DeploymentModule(
    metrics: Metrics,
    config: DeploymentConfig,
    leadershipModule: LeadershipModule,
    taskTracker: InstanceTracker,
    killService: KillService,
    launchQueue: LaunchQueue,
    scheduler: SchedulerActions,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    deploymentRepository: DeploymentRepository,
    deploymentActorProps: (ActorRef, KillService, DeploymentPlan, InstanceTracker, LaunchQueue, HealthCheckManager, EventStream, ReadinessCheckExecutor) => Props = DeploymentActor.props)(implicit val mat: Materializer) {

  private[this] val deploymentManagerActorRef: ActorRef = {
    val props = DeploymentManagerActor.props(
      metrics,
      taskTracker: InstanceTracker,
      killService,
      launchQueue,
      healthCheckManager,
      eventBus,
      readinessCheckExecutor,
      deploymentRepository,
      deploymentActorProps)

    leadershipModule.startWhenLeader(props, "deploymentManager")
  }

  val deploymentManager: DeploymentManager = new DeploymentManagerDelegate(config, deploymentManagerActorRef)
}
