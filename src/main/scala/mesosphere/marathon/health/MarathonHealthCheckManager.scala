package mesosphere.marathon.health

import java.util.concurrent.locks.{ Lock, ReentrantReadWriteLock }
import java.util.concurrent.locks.ReentrantReadWriteLock.{ ReadLock, WriteLock }
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
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.util.ThreadPoolContext.context

class MarathonHealthCheckManager @Inject() (
    system: ActorSystem,
    @Named(EventModule.busName) eventBus: EventStream,
    taskTracker: TaskTracker) extends HealthCheckManager {

  // composite key for partitioning the set of active health checks
  protected[this] case class AppVersion(id: PathId, version: Timestamp)

  protected[this] case class ActiveHealthCheck(
    healthCheck: HealthCheck,
    actor: ActorRef)

  protected[this] var appHealthChecks = Map[AppVersion, Set[ActiveHealthCheck]]()

  private[this] val rwLock = new ReentrantReadWriteLock
  private[this] val readLock = rwLock.readLock
  private[this] val writeLock = rwLock.writeLock

  /**
    * Returns the result of evaluating the supplied thunk, first obtaining the
    * lock, and releasing it afterward.
    */
  private[this] def withLock[T](lock: Lock)(op: => T): T = {
    lock.lock() // blocking
    try { op }
    finally { lock.unlock() }
  }

  protected[this] def withReadLock[T](op: => T): T = withLock(readLock)(op)
  protected[this] def withWriteLock[T](op: => T): T = withLock(writeLock)(op)

  override def list(appId: PathId): Set[HealthCheck] =
    withReadLock { listActive(appId).map(_.healthCheck) }

  protected[this] def listActive(appId: PathId): Set[ActiveHealthCheck] =
    withReadLock { appHealthChecks.withFilter(_._1.id == appId).flatMap(_._2).toSet }

  protected[this] def listActive(appId: PathId, appVersion: Timestamp): Set[ActiveHealthCheck] =
    withReadLock {
      appHealthChecks.get(AppVersion(appId, appVersion)).getOrElse(Set.empty)
    }

  override def add(appId: PathId, appVersion: Timestamp, healthCheck: HealthCheck): Unit =
    withWriteLock {
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
    withWriteLock { app.healthChecks.foreach(add(app.id, app.version, _)) }

  override def remove(appId: PathId, appVersion: Timestamp, healthCheck: HealthCheck): Unit =
    withWriteLock {
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

  override def removeAll(): Unit =
    withWriteLock { appHealthChecks.keys.map(_.id) foreach removeAllFor }

  override def removeAllFor(appId: PathId): Unit =
    withWriteLock {
      appHealthChecks.foreach { mapping =>
        val (AppVersion(id, version), ahcs) = mapping
        if (id == appId) ahcs.foreach { ahc => remove(id, version, ahc.healthCheck) }
      }
    }

  override def reconcileWith(app: AppDefinition): Unit =
    withWriteLock {
      // remove health checks for which the app version is not current and no tasks remain
      // since only current version tasks are launched.
      appHealthChecks.foreach { mapping =>
        val (AppVersion(id, version), ahcs) = mapping
        if (id == app.id) {
          val isCurrentVersion = version == app.version
          lazy val hasTasks = taskTracker.get(app.id).exists(_.getVersion == version.toString)
          if (!isCurrentVersion && !hasTasks)
            ahcs.foreach { ahc => remove(id, version, ahc.healthCheck) }
        }
      }

      // add missing health checks for the app version
      val existingHealthChecks: Set[HealthCheck] = listActive(app.id, app.version).map(_.healthCheck)
      val toAdd = app.healthChecks -- existingHealthChecks
      for (hc <- toAdd) add(app.id, app.version, hc)
    }

  override def update(taskStatus: TaskStatus, version: Timestamp): Unit =
    withReadLock {
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
    taskId: String): Future[Seq[Option[Health]]] =
    withReadLock {
      import mesosphere.marathon.health.HealthCheckActor.GetTaskHealth
      implicit val timeout: Timeout = Timeout(2, SECONDS)

      val maybeAppVersion: Option[Timestamp] = taskTracker.getVersion(appId, taskId)

      val taskHealth: Seq[Future[Option[Health]]] = maybeAppVersion.map { appVersion =>
        listActive(appId, appVersion).toSeq.collect {
          case ActiveHealthCheck(_, actor) =>
            (actor ? GetTaskHealth(taskId)).mapTo[Option[Health]]
        }
      }.getOrElse(Nil)

      Future.sequence(taskHealth)
    }

  protected[this] def deactivate(healthCheck: ActiveHealthCheck): Unit =
    withWriteLock { system stop healthCheck.actor }

}

