package mesosphere.marathon.core.health.impl

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import akka.event.EventStream
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
    eventBus: EventStream) extends Actor with ActorLogging {

  import HealthCheckWorker.HealthCheckJob
  import context.dispatcher

  var nextScheduledCheck: Option[Cancellable] = None
  var healthByInstanceId = Map.empty[Instance.Id, Health]

  val workerProps: Props = Props[HealthCheckWorkerActor]

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

  def purgeStatusOfDoneInstances(): Unit = {
    log.debug(
      "Purging health status of inactive instances for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )
    instanceTracker.instancesBySpec().map(
      _.specInstances(app.id).filter(_.isLaunched).map(_.instanceId)(collection.breakOut)
    ).onComplete { r =>
        self ! Finish(() => r match {
          case Success(activeInstanceIds) =>
            healthByInstanceId = healthByInstanceId.filterKeys(activeInstanceIds.toSet).iterator.toMap
          case Failure(e) => log.error("Error while purging status of instances", e)
        })
      }
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

  def dispatchJobs(): Unit = healthCheck match {
    case hc: MarathonHealthCheck =>
      log.debug("Dispatching health check jobs to workers")
      instanceTracker.specInstances(app.id).map(_.foreach { instance =>
        if (instance.runSpecVersion == app.version && instance.isRunning) {
          log.debug("Dispatching health check job for {}", instance.instanceId)
          val worker: ActorRef = context.actorOf(workerProps)
          worker ! HealthCheckJob(app, instance, hc)
        }
      }).onFailure({
        case e => log.error(s"An error (${e.getMessage}) has during dispatching health check jobs ", e)
      })
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
        val taskId = instance.firstTask.taskId
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

  def receive: Receive = {
    case GetInstanceHealth(instanceId) => sender() ! healthByInstanceId.getOrElse(instanceId, Health(instanceId))

    case GetAppHealth =>
      sender() ! AppHealth(healthByInstanceId.values.toSeq)

    case Tick =>
      purgeStatusOfDoneInstances()
      dispatchJobs()
      scheduleNextHealthCheck()

    case job: Finish => job.run()

    case result: HealthResult if result.version == app.version =>
      log.info("Received health result for app [{}] version [{}]: [{}]", app.id, app.version, result)
      val instanceId = result.instanceId
      val health = healthByInstanceId.getOrElse(instanceId, Health(instanceId))

      val futureHealth = result match {
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

      futureHealth.onComplete { r =>
        self ! Finish(() => () => r match {
          case Success(newHealth) =>
            healthByInstanceId += (instanceId -> newHealth)

            if (health.alive != newHealth.alive && result.publishEvent) {
              eventBus.publish(HealthStatusChanged(app.id, instanceId, result.version, alive = newHealth.alive))
              // We moved to InstanceHealthChanged Events everywhere
              // Since we perform marathon based health checks only for apps, (every task is an instance)
              // every health result is translated to an instance health changed event
              eventBus.publish(InstanceHealthChanged(instanceId, result.version, app.id, Some(newHealth.alive)))
            }
          case Failure(e) => log.error(s"Cannot update health of $instanceId", e)
        })
      }

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
    eventBus: EventStream): Props = {

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

  case class Finish(run: () => Unit)

  case class AppHealth(health: Seq[Health])
}
