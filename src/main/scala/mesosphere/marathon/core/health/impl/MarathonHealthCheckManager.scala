package mesosphere.marathon.core.health.impl

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.EventStream
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.core.event.{ AddHealthCheck, RemoveHealthCheck }
import mesosphere.marathon.core.health.impl.HealthCheckActor.{ AppHealth, GetAppHealth }
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.TaskKillService
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ AppDefinition, AppRepository, PathId, Timestamp }
import mesosphere.marathon.ZookeeperConf
import mesosphere.util.RWLock
import org.apache.mesos.Protos.TaskStatus

import scala.collection.immutable.{ Map, Seq }
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class MarathonHealthCheckManager(
    actorSystem: ActorSystem,
    killService: TaskKillService,
    eventBus: EventStream,
    taskTracker: TaskTracker,
    appRepository: AppRepository,
    zkConf: ZookeeperConf) extends HealthCheckManager {

  protected[this] case class ActiveHealthCheck(
    healthCheck: HealthCheck,
    actor: ActorRef)

  protected[this] var appHealthChecks: RWLock[mutable.Map[PathId, Map[Timestamp, Set[ActiveHealthCheck]]]] =
    RWLock(mutable.Map.empty.withDefaultValue(Map.empty.withDefaultValue(Set.empty)))

  override def list(appId: PathId): Set[HealthCheck] =
    listActive(appId).map(_.healthCheck)

  protected[this] def listActive(appId: PathId): Set[ActiveHealthCheck] =
    appHealthChecks.readLock { ahcs =>
      ahcs(appId).values.flatten.toSet
    }

  protected[this] def listActive(appId: PathId, appVersion: Timestamp): Set[ActiveHealthCheck] =
    appHealthChecks.readLock { ahcs =>
      ahcs(appId)(appVersion)
    }

  override def add(app: AppDefinition, healthCheck: HealthCheck, tasks: Iterable[Task]): Unit =
    appHealthChecks.writeLock { ahcs =>
      val healthChecksForApp = listActive(app.id, app.version)

      if (healthChecksForApp.exists(_.healthCheck == healthCheck)) {
        log.debug(s"Not adding duplicated health check for app [$app.id] and version [${app.version}]: [$healthCheck]")
      } else {
        log.info(s"Adding health check for app [${app.id}] and version [${app.version}]: [$healthCheck]")

        val ref = actorSystem.actorOf(
          HealthCheckActor.props(app, killService, healthCheck, taskTracker, eventBus))
        val newHealthChecksForApp =
          healthChecksForApp + ActiveHealthCheck(healthCheck, ref)

        if (healthCheck.protocol == Protocol.COMMAND) {
          tasks.foreach { task =>
            task.launched.foreach { launched =>
              launched.status.mesosStatus match {
                case Some(mesosStatus) if mesosStatus.hasHealthy =>
                  val health =
                    if (mesosStatus.getHealthy) Healthy(task.taskId, launched.runSpecVersion, publishEvent = false)
                    else Unhealthy(task.taskId, launched.runSpecVersion, "", publishEvent = false)
                  ref ! health
                case None =>
              }
            }
          }
        }

        val appMap = ahcs(app.id) + (app.version -> newHealthChecksForApp)
        ahcs += app.id -> appMap

        eventBus.publish(AddHealthCheck(app.id, app.version, healthCheck))
      }
    }

  override def addAllFor(app: AppDefinition, tasks: Iterable[Task]): Unit =
    appHealthChecks.writeLock { _ => // atomically add all checks for this app version
      app.healthChecks.foreach(add(app, _, tasks))
    }

  override def remove(appId: PathId, appVersion: Timestamp, healthCheck: HealthCheck): Unit =
    appHealthChecks.writeLock { ahcs =>
      val healthChecksForVersion: Set[ActiveHealthCheck] = listActive(appId, appVersion)
      val toRemove: Set[ActiveHealthCheck] = healthChecksForVersion.filter(_.healthCheck == healthCheck)
      for (ahc <- toRemove) {
        log.info(s"Removing health check for app [$appId] and version [$appVersion]: [$healthCheck]")
        deactivate(ahc)
        eventBus.publish(RemoveHealthCheck(appId))
      }
      val newHealthChecksForVersion = healthChecksForVersion -- toRemove
      val currentHealthChecksForApp = ahcs(appId)
      val newHealthChecksForApp = if (newHealthChecksForVersion.isEmpty) {
        currentHealthChecksForApp - appVersion
      } else {
        currentHealthChecksForApp + (appVersion -> newHealthChecksForVersion)
      }

      if (newHealthChecksForApp.isEmpty) ahcs -= appId
      else ahcs += (appId -> newHealthChecksForApp)
    }

  override def removeAll(): Unit =
    appHealthChecks.writeLock { _.keys foreach removeAllFor }

  override def removeAllFor(appId: PathId): Unit =
    appHealthChecks.writeLock { ahcs =>
      for {
        (version, activeHealthChecks) <- ahcs(appId)
        activeHealthCheck <- activeHealthChecks
      } {
        remove(appId, version, activeHealthCheck.healthCheck)
      }
    }

  override def reconcileWith(appId: PathId): Future[Unit] = {
    def groupTasksByVersion(tasks: Iterable[Task]): Map[Timestamp, List[Task]] =
      tasks.foldLeft(Map[Timestamp, List[Task]]()) {
        case (acc, task) =>
          task.launched match {
            case Some(launched) =>
              val version = launched.runSpecVersion
              acc + (version -> (task :: acc.getOrElse(version, Nil)))
            case None => acc
          }
      }

    appRepository.currentVersion(appId).flatMap {
      case None => Future(())
      case Some(app) =>
        log.info(s"reconcile [$appId] with latest version [${app.version}]")

        val tasks: Iterable[Task] = taskTracker.appTasksSync(app.id)
        val tasksByVersion = groupTasksByVersion(tasks)

        val activeAppVersions: Set[Timestamp] =
          tasks.iterator.flatMap(_.launched.map(_.runSpecVersion)).toSet + app.version

        val healthCheckAppVersions: Set[Timestamp] = appHealthChecks.writeLock { ahcs =>
          // remove health checks for which the app version is not current and no tasks remain
          // since only current version tasks are launched.
          for {
            (version, activeHealthChecks) <- ahcs(appId)
            if version != app.version && !activeAppVersions.contains(version)
            activeHealthCheck <- activeHealthChecks
          } remove(appId, version, activeHealthCheck.healthCheck)

          ahcs(appId).iterator.map(_._1).toSet
        }

        // add missing health checks for the current
        // reconcile all running versions of the current app
        val appVersionsWithoutHealthChecks: Set[Timestamp] = activeAppVersions -- healthCheckAppVersions
        val res: Iterator[Future[Unit]] = appVersionsWithoutHealthChecks.iterator map { version =>
          appRepository.app(app.id, version) map {
            case None =>
              // FIXME: If the app version of the task is not available anymore, no health check is started.
              // We generated a new app version for every scale change. If maxVersions is configured, we
              // throw away old versions such that we may not have the app configuration of all tasks available anymore.
              log.warn(
                s"Cannot find health check configuration for [$appId] and version [$version], " +
                  "using most recent one.")

            case Some(appVersion) =>
              log.info(s"addAllFor [$appId] version [$version]")
              addAllFor(appVersion, tasksByVersion.getOrElse(version, Seq.empty))
          }
        }
        Future.sequence(res) map { _ => () }
    }
  }

  override def update(taskStatus: TaskStatus, version: Timestamp): Unit =
    appHealthChecks.readLock { ahcs =>
      // construct a health result from the incoming task status
      val taskId = Task.Id(taskStatus.getTaskId.getValue)
      val maybeResult: Option[HealthResult] =
        if (taskStatus.hasHealthy) {
          val healthy = taskStatus.getHealthy
          log.info(s"Received status for $taskId with version [$version] and healthy [$healthy]")
          Some(if (healthy) Healthy(taskId, version) else Unhealthy(taskId, version, ""))
        } else {
          log.debug(s"Ignoring status for $taskId with no health information")
          None
        }

      // compute the app ID for the incoming task status
      val appId = Task.Id(taskStatus.getTaskId).runSpecId

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

  override def status(appId: PathId, taskId: Task.Id): Future[Seq[Health]] = {
    import HealthCheckActor.GetTaskHealth
    implicit val timeout: Timeout = Timeout(2, SECONDS)

    val futureAppVersion: Future[Option[Timestamp]] = for {
      maybeTaskState <- taskTracker.task(taskId)
    } yield maybeTaskState.flatMap(_.launched).map(_.runSpecVersion)

    futureAppVersion.flatMap {
      case None => Future.successful(Nil)
      case Some(appVersion) =>
        Future.sequence(
          listActive(appId, appVersion).iterator.collect {
          case ActiveHealthCheck(_, actor) =>
            (actor ? GetTaskHealth(taskId)).mapTo[Health]
        }.to[Seq]
        )
    }
  }

  override def statuses(appId: PathId): Future[Map[Task.Id, Seq[Health]]] =
    appHealthChecks.readLock { ahcs =>
      implicit val timeout: Timeout = Timeout(2, SECONDS)
      val futureHealths = for {
        ActiveHealthCheck(_, actor) <- ahcs(appId).values.iterator.flatten.toVector
      } yield (actor ? GetAppHealth).mapTo[AppHealth]

      Future.sequence(futureHealths) flatMap { healths =>
        val groupedHealth = healths.flatMap(_.health).groupBy(_.taskId)

        taskTracker.appTasks(appId).map { appTasks =>
          appTasks.iterator.map { task =>
            groupedHealth.get(task.taskId) match {
              case Some(xs) => task.taskId -> xs
              case None => task.taskId -> Nil
            }
          }.toMap
        }
      }
    }

  protected[this] def deactivate(healthCheck: ActiveHealthCheck): Unit =
    appHealthChecks.writeLock { _ => actorSystem stop healthCheck.actor }

}

