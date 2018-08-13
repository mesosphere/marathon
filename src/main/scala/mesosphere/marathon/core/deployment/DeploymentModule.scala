package mesosphere.marathon
package core.deployment

import akka.actor.{ActorRef, Props}
import akka.event.EventStream
import akka.stream.Materializer
import mesosphere.marathon.core.deployment.impl.{DeploymentActor, DeploymentManagerActor, DeploymentManagerDelegate}
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.scheduling.SchedulingModule
import mesosphere.marathon.storage.repository.DeploymentRepository

/**
  * Provides a [[DeploymentManager]] implementation that can be used to start and cancel a deployment and
  * to list currently running deployments.
  */
class DeploymentModule(
    metrics: Metrics,
    config: DeploymentConfig,
    leadershipModule: LeadershipModule,
    schedulingModule: SchedulingModule,
    schedulerActions: SchedulerActions,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    deploymentRepository: DeploymentRepository,
    deploymentActorProps: (ActorRef, SchedulerActions, scheduling.Scheduler, DeploymentPlan, HealthCheckManager, EventStream, ReadinessCheckExecutor) => Props = DeploymentActor.props)(implicit val mat: Materializer) {

  private[this] val deploymentManagerActorRef: ActorRef = {
    val props = DeploymentManagerActor.props(
      metrics,
      schedulerActions,
      schedulingModule.scheduler,
      healthCheckManager,
      eventBus,
      readinessCheckExecutor,
      deploymentRepository,
      deploymentActorProps)

    leadershipModule.startWhenLeader(props, "deploymentManager")
  }

  val deploymentManager: DeploymentManager = new DeploymentManagerDelegate(config, deploymentManagerActorRef)
}
