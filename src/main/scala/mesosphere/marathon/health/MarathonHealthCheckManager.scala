package mesosphere.marathon.health

import javax.inject.{ Inject, Named }
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.EventStream
import akka.pattern.ask
import akka.util.Timeout
import org.apache.mesos.Protos.TaskStatus

import mesosphere.marathon.event.{ AddHealthCheck, EventModule, RemoveHealthCheck }
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.state.{ AppDefinition, AppRepository, PathId, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.util.ThreadPoolContext.context

class MarathonHealthCheckManager @Inject() (
    system: ActorSystem,
    @Named(EventModule.busName) eventBus: EventStream,
    taskTracker: TaskTracker,
    appRepo: AppRepository) extends HealthCheckManager {

  // composite key for partitioning the set of active health checks
  protected[this] case class AppVersion(id: PathId, version: Timestamp)

  protected[this] case class ActiveHealthCheck(
    healthCheck: HealthCheck,
    actor: ActorRef)

  protected[this] var appHealthChecks = Map[AppVersion, Set[ActiveHealthCheck]]()

  override def list(appId: PathId): Set[HealthCheck] =
    listActive(appId).map(_.healthCheck)

  protected[this] def listActive(appId: PathId): Set[ActiveHealthCheck] =
    appHealthChecks.collect {
      case (AppVersion(id, _), ahc) if id == appId => ahc
    }.reduceLeft(_ ++ _)

  protected[this] def listActive(appId: PathId, appVersion: Timestamp): Set[ActiveHealthCheck] =
    appHealthChecks.get(AppVersion(appId, appVersion)).getOrElse(Set.empty)

  override def add(appId: PathId, appVersion: Timestamp, healthCheck: HealthCheck): Unit = {
    val healthChecksForApp = listActive(appId, appVersion)

    if (healthChecksForApp.exists(_.healthCheck == healthCheck))
      log.info(s"Not adding duplicate health check for app [$appId] and version [$appVersion]: [$healthCheck]")

    else {
      log.info(s"Adding health check for app [$appId] and version [$appVersion]: [$healthCheck]")
      val ref = system.actorOf(
        Props(classOf[HealthCheckActor], appId, appVersion.toString, healthCheck, taskTracker, eventBus))
      val newHealthChecksForApp =
        healthChecksForApp + ActiveHealthCheck(healthCheck, ref)
      appHealthChecks += (AppVersion(appId, appVersion) -> newHealthChecksForApp)
      eventBus.publish(AddHealthCheck(appId, appVersion, healthCheck))
    }
  }

  override def addAllFor(app: AppDefinition): Unit =
    app.healthChecks.foreach(add(app.id, app.version, _))

  override def remove(appId: PathId, appVersion: Timestamp, healthCheck: HealthCheck): Unit = {
    val healthChecksForApp: Set[ActiveHealthCheck] = listActive(appId, appVersion)
    val toRemove: Set[ActiveHealthCheck] = healthChecksForApp.filter(_.healthCheck == healthCheck)
    for (ahc <- toRemove) {
      log.info(s"Removing health check for app [$appId] and version [$appVersion]: [$healthCheck]")
      deactivate(ahc)
      eventBus.publish(RemoveHealthCheck(appId))
    }
    val newHealthChecksForApp = healthChecksForApp -- toRemove
    appHealthChecks =
      if (newHealthChecksForApp.isEmpty) appHealthChecks - AppVersion(appId, appVersion)
      else appHealthChecks + (AppVersion(appId, appVersion) -> newHealthChecksForApp)
  }

  override def removeAll(): Unit = appHealthChecks.keys.map(_.id) foreach removeAllFor

  override def removeAllFor(appId: PathId): Unit =
    appHealthChecks.foreach {
      case (AppVersion(id, version), ahcs) if id == appId =>
        ahcs.foreach { ahc => remove(id, version, ahc.healthCheck) }
      case _ => () // retained
    }

  override def reconcileWith(app: AppDefinition): Unit = {

    println(s"""\napphealthChecks:\n${appHealthChecks.mkString("\n")}\n""")

    // remove health checks for which the app version is not current and no tasks remain
    // since only current version tasks are launched.
    appHealthChecks.foreach {
      case (AppVersion(id, version), ahcs) if id == app.id =>
        val isCurrentVersion = version == app.version
        lazy val hasTasks = taskTracker.get(app.id).exists(_.getVersion == version.toString)
        if (!isCurrentVersion && !hasTasks)
          ahcs.foreach { ahc => remove(id, version, ahc.healthCheck) }
      case _ => () // retained
    }

    // add missing health checks for the app version
    val existingHealthChecks: Set[HealthCheck] = listActive(app.id, app.version).map(_.healthCheck)
    val toAdd = app.healthChecks -- existingHealthChecks
    for (hc <- toAdd) add(app.id, app.version, hc)
  }

  override def update(taskStatus: TaskStatus, version: Timestamp): Unit = {
    // construct a health result from the incoming task status
    val taskId = taskStatus.getTaskId.getValue
    val maybeResult: Option[HealthResult] =
      if (taskStatus.hasHealthy) {
        val healthy = taskStatus.getHealthy
        log.info(s"Received status for [$taskId] with version [$version] and healthy [$healthy]")
        Some(if (healthy) Healthy(taskId, version.toString) else Unhealthy(taskId, version.toString, ""))
      }
      else {
        log.info(s"Ignoring status for [$taskId] with no health information")
        None
      }

    // compute the app ID for the incoming task status
    val appId = TaskIdUtil.appId(taskStatus.getTaskId)

    // collect health check actors for the associated app's command checks.
    val healthCheckActors: Iterable[ActorRef] = listActive(appId, version).collect {
      case ActiveHealthCheck(hc, ref) if hc.protocol == Protocol.COMMAND => ref
    }

    // send the result to each health check actor
    for {
      result <- maybeResult
      ref <- healthCheckActors
    } {
      log.info(s"Forwarding health result [$result] to health check actor [$ref]")
      ref ! result
    }
  }

  override def status(
    appId: PathId,
    taskId: String): Future[Seq[Option[Health]]] = {
    import mesosphere.marathon.health.HealthCheckActor.GetTaskHealth
    implicit val timeout: Timeout = Timeout(2, SECONDS)

    val maybeAppVersion: Option[Timestamp] = taskTracker.getVersion(appId, taskId)

    val taskHealth: Seq[Future[Option[Health]]] = maybeAppVersion match {
      case (Some(appVersion)) =>
        listActive(appId, appVersion).toSeq.collect {
          case ActiveHealthCheck(_, actor) =>
            (actor ? GetTaskHealth(taskId)).mapTo[Option[Health]]
        }

      case _ => Nil
    }

    Future.sequence(taskHealth)
  }

  protected[this] def deactivate(healthCheck: ActiveHealthCheck): Unit =
    system stop healthCheck.actor

}

