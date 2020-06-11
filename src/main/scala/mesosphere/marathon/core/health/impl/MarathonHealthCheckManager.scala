package mesosphere.marathon
package core.health.impl

import akka.actor.{ActorRef, ActorRefFactory}
import akka.event.EventStream
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{MergeHub, Sink}
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.{AddHealthCheck, RemoveHealthCheck}
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.health.impl.AppHealthCheckActor.ApplicationKey
import mesosphere.marathon.core.health.impl.HealthCheckActor.{AppHealth, GetAppHealth, GetInstanceHealth}
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, Timestamp}
import mesosphere.util.RWLock
import org.apache.mesos.Protos.TaskStatus

import scala.async.Async._
import scala.collection.immutable.{Map, Seq}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

class MarathonHealthCheckManager(
    actorRefFactory: ActorRefFactory,
    killService: KillService,
    eventBus: EventStream,
    instanceTracker: InstanceTracker,
    groupManager: GroupManager,
    conf: MarathonConf)(implicit mat: ActorMaterializer) extends HealthCheckManager with StrictLogging {

  protected[this] case class ActiveHealthCheck(
      healthCheck: HealthCheck,
      actor: ActorRef)

  protected[this] var appHealthChecks: RWLock[mutable.Map[AbsolutePathId, Map[Timestamp, Set[ActiveHealthCheck]]]] =
    RWLock(mutable.Map.empty.withDefaultValue(Map.empty.withDefaultValue(Set.empty)))

  protected[this] var appHealthChecksActor: ActorRef = actorRefFactory.actorOf(AppHealthCheckActor.props(eventBus))

  override def list(appId: AbsolutePathId): Set[HealthCheck] =
    listActive(appId).map(_.healthCheck)

  protected[this] def listActive(appId: AbsolutePathId): Set[ActiveHealthCheck] =
    appHealthChecks.readLock { ahcs =>
      ahcs(appId).values.flatten.toSet
    }

  /**
    * A common materialized merge hub that is used by all [[HealthCheckActor]]s to channel the Marathon health checks.
    * Its main purpose is to provide a global throttling mechanism that limit a total number of concurrent Marathon
    * health check in the system defined by [[MarathonConf.maxConcurrentMarathonHealthChecks]] parameter. After
    * executing the health check by calling the [[HealthCheckWorker.run()]] method the result is sent back to the
    * HealthCheckActor via the provided ActorRef.
    */
  val healthCheckWorkerHub: Sink[(AppDefinition, Instance, MarathonHealthCheck, ActorRef), NotUsed] =
    MergeHub
      .source[(AppDefinition, Instance, MarathonHealthCheck, ActorRef)](1)
      .mapAsync(conf.maxConcurrentMarathonHealthChecks()){
        case (app, instance, marathonHealthCheck, actorRef) =>
          HealthCheckWorker
            .run(app, instance, marathonHealthCheck)
            .map(healthResult => actorRef ! healthResult)
            .recover {
              // Theoretically we should never get there: [[HealthCheckWorker.run]] already wraps failed health checks into
              // successful futures with Healthy/Unhealthy message. But just in case the underlying implementation changes...
              case NonFatal(e) =>
                logger.warn(s"HealthCheck stream for app ${app.id} version ${app.version} " +
                  s"and healthCheck $marathonHealthCheck failed with: ", e)
            }
      }
      .to(Sink.ignore)
      .run()

  protected[this] def listActive(appId: AbsolutePathId, appVersion: Timestamp): Set[ActiveHealthCheck] =
    appHealthChecks.readLock { ahcs =>
      ahcs(appId)(appVersion)
    }

  override def add(app: AppDefinition, healthCheck: HealthCheck, instances: Seq[Instance]): Unit =
    appHealthChecks.writeLock { ahcs =>
      val healthChecksForApp = listActive(app.id, app.version)

      if (healthChecksForApp.exists(_.healthCheck == healthCheck)) {
        logger.debug(s"Not adding duplicated health check for app [$app.id] and version [${app.version}]: [$healthCheck]")
      } else {
        logger.info(s"Adding health check for app [${app.id}] and version [${app.version}]: [$healthCheck]")

        val ref = actorRefFactory.actorOf(
          HealthCheckActor.props(app, appHealthChecksActor, killService, healthCheck, instanceTracker, eventBus, healthCheckWorkerHub))
        val newHealthChecksForApp =
          healthChecksForApp + ActiveHealthCheck(healthCheck, ref)

        appHealthChecksActor ! AppHealthCheckActor.AddHealthCheck(
          ApplicationKey(app.id, app.version), healthCheck)

        healthCheck match {
          case _: MesosHealthCheck =>
            instances.foreach { instance =>
              instance.tasksMap.values.withFilter(_.isRunning).map(_.status.mesosStatus).foreach {
                case Some(mesosStatus) if mesosStatus.hasHealthy =>
                  val health = if (mesosStatus.getHealthy) Healthy(instance.instanceId, instance.runSpecVersion, publishEvent = false)
                  else Unhealthy(instance.instanceId, instance.runSpecVersion, "", publishEvent = false)
                  ref ! health
                case None =>
              }
            }
          case _ =>
        }

        val appMap = ahcs(app.id) + (app.version -> newHealthChecksForApp)
        ahcs += app.id -> appMap

        eventBus.publish(AddHealthCheck(app.id, app.version, healthCheck))
      }
    }

  override def addAllFor(app: AppDefinition, instances: Seq[Instance]): Unit =
    appHealthChecks.writeLock { _ => // atomically add all checks for this app version
      app.healthChecks.foreach(add(app, _, instances))
    }

  override def remove(appId: AbsolutePathId, appVersion: Timestamp, healthCheck: HealthCheck): Unit =
    appHealthChecks.writeLock { ahcs =>
      val healthChecksForVersion: Set[ActiveHealthCheck] = listActive(appId, appVersion)
      val toRemove: Set[ActiveHealthCheck] = healthChecksForVersion.filter(_.healthCheck == healthCheck)
      for (ahc <- toRemove) {
        logger.info(s"Removing health check for app [$appId] and version [$appVersion]: [$healthCheck]")
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

      appHealthChecksActor ! AppHealthCheckActor.RemoveHealthCheck(
        ApplicationKey(appId, appVersion), healthCheck)

      if (newHealthChecksForApp.isEmpty) ahcs -= appId
      else ahcs += (appId -> newHealthChecksForApp)
    }

  override def removeAll(): Unit =
    appHealthChecks.writeLock {
      _.keys foreach removeAllFor
    }

  override def removeAllFor(appId: AbsolutePathId): Unit =
    appHealthChecks.writeLock { ahcs =>
      for {
        (version, activeHealthChecks) <- ahcs(appId)
        activeHealthCheck <- activeHealthChecks
      } {
        remove(appId, version, activeHealthCheck.healthCheck)
      }
    }

  /**
    * Reconcile all health checks for all instances of the given apps.
    * Note: there can be Instances with different versions of the application.
    *       This reconciliation logic will always use the related version of the application to reconcile health checks.
    *
    * @param apps all applications to reconcile
    * @return a future which will be complete, if the reconciliation for all apps is done.
    */
  override def reconcile(apps: Seq[AppDefinition]): Future[Done] = {

    def reconcileApp(app: AppDefinition, instances: Seq[Instance]): Future[Done] = {
      val appId = app.id
      logger.info(s"reconcile $appId with latest version ${app.version} for instances: $instances")

      val instancesByVersion = instances.groupBy(_.runSpecVersion)

      val activeAppVersions: Set[Timestamp] = {
        val versions: Set[Timestamp] = instances.iterator.map(_.runSpecVersion).toSet
        versions + app.version
      }

      val healthCheckAppVersions: Set[Timestamp] = appHealthChecks.writeLock { ahcs =>
        // remove health checks for which the app version is not current and no tasks remain
        // since only current version tasks are launched.
        for {
          (version, activeHealthChecks) <- ahcs(appId)
          if version < app.version && !activeAppVersions.contains(version)
          activeHealthCheck <- activeHealthChecks
        } remove(appId, version, activeHealthCheck.healthCheck)

        ahcs(appId).keySet
      }

      // add missing health checks for the current
      // reconcile all running versions of the current app
      val appVersionsWithoutHealthChecks: Set[Timestamp] = activeAppVersions -- healthCheckAppVersions
      val res: Set[Future[Unit]] = appVersionsWithoutHealthChecks.map { version =>
        groupManager.appVersion(appId, version.toOffsetDateTime).map {
          case None =>
            // FIXME: If the app version of the task is not available anymore, no health check is started.
            // We generated a new app version for every scale change. If maxVersions is configured, we
            // throw away old versions such that we may not have the app configuration of all tasks available anymore.
            logger.warn(
              s"Cannot find health check configuration for [$appId] and version [$version], " +
                "using most recent one.")

          case Some(appVersion) =>
            logger.info(s"addAllFor [$appId] version [$version]")
            addAllFor(appVersion, instancesByVersion.getOrElse(version, Seq.empty))
        }
      }
      Future.sequence(res).map(_ => Done)
    }

    async {
      val instances = await(instanceTracker.instancesBySpec())
      val reconciledApps = apps.map(app => reconcileApp(app, instances.specInstances(app.id)))
      await(Future.sequence(reconciledApps).map(_ => Done))
    }
  }

  override def update(taskStatus: TaskStatus, version: Timestamp): Unit =
    appHealthChecks.readLock { ahcs =>
      // construct a health result from the incoming task status
      val instanceId = Task.Id.parse(taskStatus.getTaskId).instanceId
      val maybeResult: Option[HealthResult] =
        if (taskStatus.hasHealthy) {
          val healthy = taskStatus.getHealthy
          logger.info(s"Received status for $instanceId with version [$version] and healthy [$healthy]")
          Some(if (healthy) Healthy(instanceId, version) else Unhealthy(instanceId, version, ""))
        } else {
          logger.debug(s"Ignoring status for $instanceId with no health information")
          None
        }

      // collect health check actors for the associated app's Mesos checks.
      val healthCheckActors: Seq[ActorRef] = listActive(instanceId.runSpecId, version).iterator.collect {
        case ActiveHealthCheck(hc: MesosHealthCheck, ref) => ref
      }.toSeq

      // send the result to each health check actor
      for {
        result <- maybeResult
        ref <- healthCheckActors
      } {
        logger.info(s"Forwarding health result [$result] to health check actor [$ref]")
        ref ! result
      }
    }

  override def status(appId: AbsolutePathId, instanceId: Instance.Id): Future[Seq[Health]] = {
    implicit val timeout: Timeout = Timeout(2, SECONDS)

    val futureAppVersion: Future[Option[Timestamp]] = for {
      maybeTaskState <- instanceTracker.instance(instanceId)
    } yield maybeTaskState.map(_.runSpecVersion)

    futureAppVersion.flatMap {
      case None => Future.successful(Nil)
      case Some(appVersion) =>
        Future.sequence(
          listActive(appId, appVersion).iterator.collect {
            case ActiveHealthCheck(_, actor) =>
              (actor ? GetInstanceHealth(instanceId)).mapTo[Health]
          }.toSeq
        )
    }
  }

  override def statuses(appId: AbsolutePathId): Future[Map[Instance.Id, Seq[Health]]] = {
    appHealthChecks.readLock { ahcs =>
      implicit val timeout: Timeout = Timeout(2, SECONDS)
      val futureHealths: Seq[Future[HealthCheckActor.AppHealth]] = ahcs(appId).values.iterator.flatMap { checks =>
        checks.map {
          case ActiveHealthCheck(_, actor) => (actor ? GetAppHealth).mapTo[AppHealth]
        }
      }.toSeq

      Future.sequence(futureHealths).map { healths =>
        healths.flatMap(_.health).groupBy(_.instanceId).withDefaultValue(Nil)
      }
    }
  }

  protected[this] def deactivate(healthCheck: ActiveHealthCheck): Unit =
    appHealthChecks.writeLock { _ => actorRefFactory.stop(healthCheck.actor) }

}
