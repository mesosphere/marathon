package mesosphere.marathon
package core.health.impl

import akka.NotUsed
import akka.actor.{Actor, ActorRef, Props}
import akka.event.EventStream
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.health.impl.AppHealthCheckActor.{ApplicationKey, HealthCheckStatusChanged, InstanceKey, PurgeHealthCheckStatuses}
import mesosphere.marathon.core.health.impl.HealthCheckActor._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.termination.{KillReason, KillService}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, Timestamp}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

private[health] class HealthCheckActor(
    app: AppDefinition,
    appHealthCheckActor: ActorRef,
    killService: KillService,
    healthCheck: HealthCheck,
    instanceTracker: InstanceTracker,
    eventBus: EventStream,
    healthCheckHub: Sink[(AppDefinition, Instance, MarathonHealthCheck, ActorRef), NotUsed])
  extends Actor with StrictLogging {

  implicit val mat = ActorMaterializer()
  import context.dispatcher

  val healthByInstanceId = TrieMap.empty[Instance.Id, Health]

  override def preStart(): Unit = {
    healthCheck match {
      case marathonHealthCheck: MarathonHealthCheck =>
        //Start health checking not after the default first health check
        val startAfter = math.min(marathonHealthCheck.interval.toMillis, HealthCheck.DefaultFirstHealthCheckAfter.toMillis).millis

        logger.info(s"Starting health check for ${app.id} version ${app.version} and healthCheck $marathonHealthCheck in $startAfter ms")

        Source
          .tick(startAfter, marathonHealthCheck.interval, Tick)
          .mapAsync(1)(_ => instanceTracker.specInstances(app.id))
          .map { instances =>
            purgeStatusOfDoneInstances(instances)
            instances.collect {
              case instance if instance.runSpecVersion == app.version && instance.isRunning =>
                logger.debug("Making a health check request for {}", instance.instanceId)
                (app, instance, marathonHealthCheck, self)
            }
          }
          .mapConcat(identity)
          .watchTermination(){ (_, done) =>
            done.onComplete {
              case Success(_) =>
                logger.info(s"HealthCheck stream for app ${app.id} version ${app.version} and healthCheck $healthCheck was stopped")

              case Failure(ex) =>
                logger.warn(s"HealthCheck stream for app ${app.id} version ${app.version} and healthCheck $healthCheck crashed due to:", ex)
                self ! 'restart
            }
          }
          .runWith(healthCheckHub)
      case _ => // Don't do anything for Mesos health checks
    }
  }

  def purgeStatusOfDoneInstances(instances: Seq[Instance]): Unit = {
    logger.debug(s"Purging health status of inactive instances for app ${app.id} version ${app.version} and healthCheck ${healthCheck}")

    val inactiveInstanceIds: Set[Instance.Id] = instances.filterNot(_.isActive).iterator.map(_.instanceId).toSet
    inactiveInstanceIds.foreach { inactiveId =>
      healthByInstanceId.remove(inactiveId)
    }

    val checksToPurge = instances.withFilter(!_.isActive).map(instance => {
      val instanceKey = InstanceKey(ApplicationKey(instance.runSpecId, instance.runSpecVersion), instance.instanceId)
      (instanceKey, healthCheck)
    })
    appHealthCheckActor ! PurgeHealthCheckStatuses(checksToPurge)
  }

  def checkConsecutiveFailures(instance: Instance, health: Health): Unit = {
    val consecutiveFailures = health.consecutiveFailures
    val maxFailures = healthCheck.maxConsecutiveFailures

    // ignore failures if maxFailures == 0
    if (consecutiveFailures >= maxFailures && maxFailures > 0) {
      val instanceId = instance.instanceId
      logger.info(
        s"Detected unhealthy $instanceId of app [${app.id}] version [${app.version}] on host ${instance.hostname}"
      )

      // kill the instance, if it is reachable
      if (instance.isUnreachable) {
        logger.info(s"Instance $instanceId on host ${instance.hostname} is temporarily unreachable. Performing no kill.")
      } else {
        logger.info(s"Send kill request for $instanceId on host ${instance.hostname.getOrElse("unknown")} to driver")
        require(instance.tasksMap.size == 1, "Unexpected pod instance in HealthCheckActor")
        val taskId = instance.appTask.taskId
        eventBus.publish(
          UnhealthyInstanceKillEvent(
            appId = instance.runSpecId,
            taskId = taskId,
            instanceId = instanceId,
            version = app.version,
            reason = health.lastFailureCause.getOrElse("unknown"),
            host = instance.hostname.getOrElse("unknown"),
            slaveId = instance.agentInfo.flatMap(_.agentId),
            timestamp = health.lastFailure.getOrElse(Timestamp.now()).toString
          )
        )
        killService.killInstancesAndForget(Seq(instance), KillReason.FailedHealthChecks)
      }
    }
  }

  def ignoreFailures(instance: Instance, health: Health): Boolean = {
    // ignore all failures during the grace period aa well as for instances that are not running
    if (instance.isRunning) {
      // ignore if we haven't had a successful health check yet and are within the grace period
      health.firstSuccess.isEmpty && instance.state.since + healthCheck.gracePeriod > Timestamp.now()
    } else {
      true
    }
  }

  def handleHealthResult(result: HealthResult): Unit = {
    val instanceId = result.instanceId
    val health = healthByInstanceId.getOrElse(instanceId, Health(instanceId))

    val updatedHealth = result match {
      case Healthy(_, _, _, _) =>
        Future.successful(health.update(result))
      case Unhealthy(_, _, _, _, _) =>
        instanceTracker.instance(instanceId).map {
          case Some(instance) =>
            if (ignoreFailures(instance, health)) {
              // Don't update health
              health
            } else {
              logger.debug("{} is {}", instanceId, result)
              if (result.publishEvent) {
                eventBus.publish(FailedHealthCheck(app.id, instanceId, healthCheck))
              }
              checkConsecutiveFailures(instance, health)
              health.update(result)
            }
          case None =>
            logger.error(s"Couldn't find instance $instanceId")
            health.update(result)
        }
      case _: Ignored =>
        Future.successful(health) // Ignore and keep the old health
    }
    updatedHealth.onComplete {
      case Success(newHealth) => self ! InstanceHealth(result, health, newHealth)
      case Failure(t) => logger.error(s"An error has occurred: ${t.getMessage}", t)
    }
  }

  def updateInstanceHealth(instanceHealth: InstanceHealth): Unit = {
    val result = instanceHealth.result
    val instanceId = result.instanceId
    val health = instanceHealth.health
    val newHealth = instanceHealth.newHealth

    logger.info(s"Received health result for app [${app.id}] version [${app.version}]: [$result]")
    healthByInstanceId += (instanceId -> instanceHealth.newHealth)
    appHealthCheckActor ! HealthCheckStatusChanged(ApplicationKey(app.id, app.version), healthCheck, newHealth)

    if (health.alive != newHealth.alive && result.publishEvent) {
      eventBus.publish(HealthStatusChanged(app.id, instanceId, result.version, alive = newHealth.alive))
    }
  }

  def receive: Receive = {
    case GetInstanceHealth(instanceId) => sender() ! healthByInstanceId.getOrElse(instanceId, Health(instanceId))

    case GetAppHealth =>
      sender() ! AppHealth(healthByInstanceId.values.to(Seq))

    case result: HealthResult if result.version == app.version =>
      handleHealthResult(result)

    case instanceHealth: InstanceHealth =>
      updateInstanceHealth(instanceHealth)

    case 'restart =>
      throw new RuntimeException("HealthCheckActor stream stopped, restarting")
  }
}

object HealthCheckActor {
  def props(
    app: AppDefinition,
    appHealthCheckActor: ActorRef,
    killService: KillService,
    healthCheck: HealthCheck,
    instanceTracker: InstanceTracker,
    eventBus: EventStream,
    healthCheckHub: Sink[(AppDefinition, Instance, MarathonHealthCheck, ActorRef), NotUsed]): Props = {

    Props(new HealthCheckActor(
      app,
      appHealthCheckActor,
      killService,
      healthCheck,
      instanceTracker,
      eventBus,
      healthCheckHub))
  }

  // self-sent every healthCheck.intervalSeconds
  case object Tick
  case class GetInstanceHealth(instanceId: Instance.Id)
  case object GetAppHealth

  case class AppHealth(health: Seq[Health])

  case class InstanceHealth(result: HealthResult, health: Health, newHealth: Health)
  case class InstancesUpdate(version: Timestamp, instances: Seq[Instance])
}
