package mesosphere.marathon
package core.health.impl

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import akka.event.EventStream
import akka.stream.Materializer
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.health.impl.HealthCheckActor._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, Timestamp }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

private[health] class HealthCheckActor(
    app: AppDefinition,
    killService: KillService,
    healthCheck: HealthCheck,
    instanceTracker: InstanceTracker,
    eventBus: EventStream)(implicit mat: Materializer) extends Actor with ActorLogging {

  import HealthCheckWorker.HealthCheckJob
  import context.dispatcher

  var nextScheduledCheck: Option[Cancellable] = None
  var healthByInstanceId = Map.empty[Instance.Id, Health]

  val workerProps: Props = Props(classOf[HealthCheckWorkerActor], mat)

  override def preStart(): Unit = {
    log.info(
      "Starting health check actor for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )
    //Start health checking not after the default first health check
    val start = math.min(healthCheck.interval.toMillis, HealthCheck.DefaultFirstHealthCheckAfter.toMillis).millis
    scheduleNextHealthCheck(Some(start))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    log.info(
      "Restarting health check actor for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )

  override def postStop(): Unit = {
    nextScheduledCheck.forall { _.cancel() }
    log.info(
      "Stopped health check actor for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )
  }

  def updateInstances(): Unit = {
    instanceTracker.specInstances(app.id).onComplete {
      case Success(instances) => self ! InstancesUpdate(version = app.version, instances = instances)
      case Failure(t) => log.error("An error has occurred: " + t.getMessage, t)
    }
  }

  def purgeStatusOfDoneInstances(instances: Seq[Instance]): Unit = {
    log.debug(
      "Purging health status of inactive instances for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )
    val activeInstanceIds: Set[Instance.Id] = instances.withFilter(_.isLaunched).map(_.instanceId)(collection.breakOut)
    // The Map built with filterKeys wraps the original map and contains a reference to activeInstanceIds.
    // Therefore we materialize it into a new map.
    healthByInstanceId = healthByInstanceId.filterKeys(activeInstanceIds).iterator.toMap
  }

  def scheduleNextHealthCheck(interval: Option[FiniteDuration] = None): Unit = healthCheck match {
    case hc: MarathonHealthCheck =>
      log.debug(
        "Scheduling next health check for app [{}] version [{}] and healthCheck [{}]",
        app.id,
        app.version,
        hc
      )
      nextScheduledCheck = Some(
        context.system.scheduler.scheduleOnce(interval.getOrElse(hc.interval)) {
          self ! Tick
        }
      )
    case _ => // Don't do anything for Mesos health checks
  }

  def dispatchJobs(instances: Seq[Instance]): Unit = healthCheck match {
    case hc: MarathonHealthCheck =>
      log.debug("Dispatching health check jobs to workers")
      instances.foreach { instance =>
        if (instance.runSpecVersion == app.version && instance.isRunning) {
          log.debug("Dispatching health check job for {}", instance.instanceId)
          val worker: ActorRef = context.actorOf(workerProps)
          worker ! HealthCheckJob(app, instance, hc)
        }
      }
    case _ => // Don't do anything for Mesos health checks
  }

  def checkConsecutiveFailures(instance: Instance, health: Health): Unit = {
    val consecutiveFailures = health.consecutiveFailures
    val maxFailures = healthCheck.maxConsecutiveFailures

    // ignore failures if maxFailures == 0
    if (consecutiveFailures >= maxFailures && maxFailures > 0) {
      val instanceId = instance.instanceId
      log.info(
        s"Detected unhealthy $instanceId of app [${app.id}] version [${app.version}] on host ${instance.agentInfo.host}"
      )

      // kill the instance, if it is reachable
      if (instance.isUnreachable) {
        log.info(s"Instance $instanceId on host ${instance.agentInfo.host} is temporarily unreachable. Performing no kill.")
      } else {
        log.info(s"Send kill request for $instanceId on host ${instance.agentInfo.host} to driver")
        require(instance.tasksMap.size == 1, "Unexpected pod instance in HealthCheckActor")
        val taskId = instance.appTask.taskId
        eventBus.publish(
          UnhealthyInstanceKillEvent(
            appId = instance.runSpecId,
            taskId = taskId,
            instanceId = instanceId,
            version = app.version,
            reason = health.lastFailureCause.getOrElse("unknown"),
            host = instance.agentInfo.host,
            slaveId = instance.agentInfo.agentId,
            timestamp = health.lastFailure.getOrElse(Timestamp.now()).toString
          )
        )
        killService.killInstance(instance, KillReason.FailedHealthChecks)
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
        Future(health.update(result))
      case Unhealthy(_, _, _, _, _) =>
        instanceTracker.instance(instanceId).map({
          case Some(instance) =>
            if (ignoreFailures(instance, health)) {
              // Don't update health
              health
            } else {
              log.debug("{} is {}", instance.instanceId, result)
              if (result.publishEvent) {
                eventBus.publish(FailedHealthCheck(app.id, instanceId, healthCheck))
              }
              checkConsecutiveFailures(instance, health)
              health.update(result)
            }
          case None =>
            log.error(s"Couldn't find instance $instanceId")
            health.update(result)
        })
    }
    updatedHealth.onComplete {
      case Success(newHealth) => self ! InstanceHealth(result, health, newHealth)
      case Failure(t) => log.error("An error has occurred: " + t.getMessage, t)
    }
  }

  def updateInstanceHealth(instanceHealth: InstanceHealth): Unit = {
    val result = instanceHealth.result
    val instanceId = result.instanceId
    val health = instanceHealth.health
    val newHealth = instanceHealth.newHealth

    log.info("Received health result for app [{}] version [{}]: [{}]", app.id, app.version, result)
    healthByInstanceId += (instanceId -> instanceHealth.newHealth)

    if (health.alive != newHealth.alive && result.publishEvent) {
      eventBus.publish(HealthStatusChanged(app.id, instanceId, result.version, alive = newHealth.alive))
      // We moved to InstanceHealthChanged Events everywhere
      // Since we perform marathon based health checks only for apps, (every task is an instance)
      // every health result is translated to an instance health changed event
      eventBus.publish(InstanceHealthChanged(instanceId, result.version, app.id, Some(newHealth.alive)))
    }
  }

  def receive: Receive = {
    case GetInstanceHealth(instanceId) => sender() ! healthByInstanceId.getOrElse(instanceId, Health(instanceId))

    case GetAppHealth =>
      sender() ! AppHealth(healthByInstanceId.values.to[Seq])

    case Tick =>
      updateInstances()
      scheduleNextHealthCheck()

    case InstancesUpdate(version, instances) if version == app.version =>
      purgeStatusOfDoneInstances(instances)
      dispatchJobs(instances)

    case result: HealthResult if result.version == app.version =>
      handleHealthResult(result)

    case instanceHealth: InstanceHealth =>
      updateInstanceHealth(instanceHealth)

    case result: HealthResult =>
      log.warning(s"Ignoring health result [$result] due to version mismatch.")

  }
}

object HealthCheckActor {
  def props(
    app: AppDefinition,
    killService: KillService,
    healthCheck: HealthCheck,
    instanceTracker: InstanceTracker,
    eventBus: EventStream)(implicit mat: Materializer): Props = {

    Props(new HealthCheckActor(
      app,
      killService,
      healthCheck,
      instanceTracker,
      eventBus))
  }

  // self-sent every healthCheck.intervalSeconds
  case object Tick
  case class GetInstanceHealth(instanceId: Instance.Id)
  case object GetAppHealth

  case class AppHealth(health: Seq[Health])

  case class InstanceHealth(result: HealthResult, health: Health, newHealth: Health)
  case class InstancesUpdate(version: Timestamp, instances: Seq[Instance])

}
