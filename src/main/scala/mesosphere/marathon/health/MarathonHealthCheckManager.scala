package mesosphere.marathon.health

import java.util.concurrent.locks.{ Lock, ReentrantReadWriteLock }
import javax.inject.{ Inject, Named }

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.EventStream
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.{ MarathonScheduler, MarathonSchedulerDriverHolder }
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event.{ AddHealthCheck, EventModule, RemoveHealthCheck }
import mesosphere.marathon.health.HealthCheckActor.{ AppHealth, GetAppHealth }
import mesosphere.marathon.state.{ AppDefinition, AppRepository, PathId, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.util.ThreadPoolContext.context
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future
import scala.concurrent.duration._

class MarathonHealthCheckManager @Inject() (
    system: ActorSystem,
    scheduler: MarathonScheduler,
    driverHolder: MarathonSchedulerDriverHolder,
    @Named(EventModule.busName) eventBus: EventStream,
    taskTracker: TaskTracker,
    appRepository: AppRepository) extends HealthCheckManager {

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
      appHealthChecks.getOrElse(AppVersion(appId, appVersion), Set.empty)
    }

  override def add(appId: PathId, appVersion: Timestamp, healthCheck: HealthCheck): Unit =
    withWriteLock {
      val healthChecksForApp = listActive(appId, appVersion)

      if (healthChecksForApp.exists(_.healthCheck == healthCheck))
        log.debug(s"Not adding duplicate health check for app [$appId] and version [$appVersion]: [$healthCheck]")

      else {
        log.info(s"Adding health check for app [$appId] and version [$appVersion]: [$healthCheck]")
        val ref = system.actorOf(
          Props(classOf[HealthCheckActor],
            appId, appVersion.toString, driverHolder, scheduler, healthCheck, taskTracker, eventBus))
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

  override def reconcileWith(appId: PathId): Future[Unit] =
    appRepository.currentVersion(appId) flatMap {
      case None => Future(())
      case Some(app) => withWriteLock {
        val versionTasksMap: Map[String, MarathonTask] =
          taskTracker.get(app.id).map { task => task.getVersion -> task }.toMap

        // remove health checks for which the app version is not current and no tasks remain
        // since only current version tasks are launched.
        appHealthChecks.foreach { mapping =>
          val (AppVersion(id, version), ahcs) = mapping
          if (id == app.id) {
            val isCurrentVersion = version == app.version
            lazy val hasTasks = versionTasksMap.contains(version.toString)
            if (!isCurrentVersion && !hasTasks)
              ahcs.foreach { ahc => remove(id, version, ahc.healthCheck) }
          }
        }

        // add missing health checks for the current
        // reconcile all running versions of the current app
        val res = versionTasksMap.keys map { version =>
          appRepository.app(app.id, Timestamp(version)) map {
            case None             =>
            case Some(appVersion) => addAllFor(appVersion)
          }
        }
        Future.sequence(res) map { _ => () }
      }
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
          log.debug(s"Ignoring status for [$taskId] with no health information")
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
    taskId: String): Future[Seq[Health]] =
    withReadLock {
      import mesosphere.marathon.health.HealthCheckActor.GetTaskHealth
      implicit val timeout: Timeout = Timeout(2, SECONDS)

      val maybeAppVersion: Option[Timestamp] = taskTracker.getVersion(appId, taskId)

      val taskHealth: Seq[Future[Health]] = maybeAppVersion.map { appVersion =>
        listActive(appId, appVersion).toSeq.collect {
          case ActiveHealthCheck(_, actor) =>
            (actor ? GetTaskHealth(taskId)).mapTo[Health]
        }
      }.getOrElse(Nil)

      Future.sequence(taskHealth)
    }

  override def statuses(appId: PathId): Future[Map[String, Seq[Health]]] = withReadLock {
    val futureVersions = appRepository.listVersions(appId)
    implicit val timeout: Timeout = Timeout(2, SECONDS)

    futureVersions flatMap { versions =>
      val futureHealths = for {
        version <- versions
        ActiveHealthCheck(_, actor) <- listActive(appId, version)
      } yield (actor ? GetAppHealth).mapTo[AppHealth]

      Future.sequence(futureHealths) map { healths =>
        val groupedHealth = healths.flatMap(_.health).groupBy(_.taskId)

        taskTracker.get(appId).toSeq.map { task =>
          groupedHealth.get(task.getId) match {
            case Some(xs) => task.getId -> xs.toSeq
            case None     => task.getId -> Nil
          }
        }.toMap
      }
    }
  }

  override def healthCounts(appId: PathId): Future[HealthCounts] =
    withReadLock {

      statuses(appId).map { statusMap =>
        val builder = HealthCounts.newBuilder
        statusMap.values foreach { statuses =>
          if (statuses.isEmpty) {
            builder.incUnknown()
          }
          else if (statuses.forall(_.alive)) {
            builder.incHealthy()
          }
          else {
            builder.incUnhealthy()
          }
        }

        builder.result()
      }
    }

  protected[this] def deactivate(healthCheck: ActiveHealthCheck): Unit =
    withWriteLock { system stop healthCheck.actor }

}

