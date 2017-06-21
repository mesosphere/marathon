package mesosphere.marathon
package core.appinfo.impl

import mesosphere.marathon.core.appinfo.{ AppInfo, EnrichedTask, TaskCounts, TaskStatsByVersion }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.deployment.{ DeploymentPlan, DeploymentStepInfo }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{ Health, HealthCheckManager }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.ReadinessCheckResult
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.raml.{ PodInstanceState, PodInstanceStatus, PodState, PodStatus, Raml }
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.TaskFailureRepository
import org.slf4j.LoggerFactory

import scala.async.Async.{ async, await }
import scala.collection.immutable.{ Map, Seq }
import scala.concurrent.Future
import scala.util.control.NonFatal

// TODO(jdef) pods rename this to something like ResourceInfoBaseData
class AppInfoBaseData(
    clock: Clock,
    instanceTracker: InstanceTracker,
    healthCheckManager: HealthCheckManager,
    deploymentService: DeploymentService,
    taskFailureRepository: TaskFailureRepository,
    groupManager: GroupManager) {

  import AppInfoBaseData._
  import mesosphere.marathon.core.async.ExecutionContexts.global

  if (log.isDebugEnabled) log.debug(s"new AppInfoBaseData $this")

  lazy val runningDeployments: Future[Seq[DeploymentStepInfo]] = deploymentService.listRunningDeployments()

  lazy val readinessChecksByAppFuture: Future[Map[PathId, Seq[ReadinessCheckResult]]] = {
    runningDeployments.map { infos =>
      infos.foldLeft(Map.empty[PathId, Vector[ReadinessCheckResult]].withDefaultValue(Vector.empty)) { (result, info) =>
        result ++ info.readinessChecksByApp.map {
          case (appId, checkResults) => appId -> (result(appId) ++ checkResults)
        }
      }
    }
  }

  lazy val runningDeploymentsByAppFuture: Future[Map[PathId, Seq[Identifiable]]] = {
    log.debug("Retrieving running deployments")

    val allRunningDeploymentsFuture: Future[Seq[DeploymentPlan]] = runningDeployments.map(_.map(_.plan))

    allRunningDeploymentsFuture.map { allDeployments =>
      val byApp = Map.empty[PathId, Vector[DeploymentPlan]].withDefaultValue(Vector.empty)
      val deploymentsByAppId = allDeployments.foldLeft(byApp) { (result, deploymentPlan) =>
        deploymentPlan.affectedRunSpecIds.foldLeft(result) { (result, appId) =>
          val newEl = appId -> (result(appId) :+ deploymentPlan)
          result + newEl
        }
      }
      deploymentsByAppId
        .map { case (id, deployments) => id -> deployments.map(deploymentPlan => Identifiable(deploymentPlan.id)) }
        .withDefaultValue(Seq.empty)
    }
  }

  lazy val instancesByRunSpecFuture: Future[InstanceTracker.InstancesBySpec] = {
    log.debug("Retrieve tasks")
    instanceTracker.instancesBySpec()
  }

  def appInfoFuture(app: AppDefinition, embed: Set[AppInfo.Embed]): Future[AppInfo] = {
    val appData = new AppData(app)
    embed.foldLeft(Future.successful(AppInfo(app))) { (infoFuture, embed) =>
      infoFuture.flatMap { info =>
        embed match {
          case AppInfo.Embed.Counts =>
            appData.taskCountsFuture.map(counts => info.copy(maybeCounts = Some(counts)))
          case AppInfo.Embed.Readiness =>
            readinessChecksByAppFuture.map(checks => info.copy(maybeReadinessCheckResults = Some(checks(app.id))))
          case AppInfo.Embed.Deployments =>
            runningDeploymentsByAppFuture.map(deployments => info.copy(maybeDeployments = Some(deployments(app.id))))
          case AppInfo.Embed.LastTaskFailure =>
            appData.maybeLastTaskFailureFuture.map { maybeLastTaskFailure =>
              info.copy(maybeLastTaskFailure = maybeLastTaskFailure)
            }
          case AppInfo.Embed.Tasks =>
            appData.enrichedTasksFuture.map(tasks => info.copy(maybeTasks = Some(tasks)))
          case AppInfo.Embed.TaskStats =>
            appData.taskStatsFuture.map(taskStats => info.copy(maybeTaskStats = Some(taskStats)))
        }
      }
    }
  }

  /**
    * Contains app-sepcific data that we need to retrieved.
    *
    * All data is lazy such that only data that is actually needed for the requested embedded information
    * gets retrieved.
    */
  private[this] class AppData(app: AppDefinition) {
    lazy val now: Timestamp = clock.now()

    lazy val instancesByIdFuture: Future[Map[Instance.Id, Instance]] = instancesByRunSpecFuture.map(_.specInstances(app.id)
      .foldLeft(Map.newBuilder[Instance.Id, Instance]) { (result, instance) => result += instance.instanceId -> instance }
      .result()
    )

    lazy val instancesFuture: Future[Seq[Instance]] = instancesByIdFuture.map(_.values.to[Seq])

    lazy val healthByInstanceIdFuture: Future[Map[Instance.Id, Seq[Health]]] = {
      log.debug(s"retrieving health counts for app [${app.id}]")
      healthCheckManager.statuses(app.id)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while retrieving health counts for app [${app.id}]", e)
    }

    lazy val tasksForStats: Future[Seq[TaskForStatistics]] = {
      for {
        instances <- instancesFuture
        healthCounts <- healthByInstanceIdFuture
      } yield TaskForStatistics.forInstances(now, instances, healthCounts)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while calculating tasksForStats for app [${app.id}]", e)
    }

    lazy val taskCountsFuture: Future[TaskCounts] = {
      log.debug(s"calculating task counts for app [${app.id}]")
      for {
        tasks <- tasksForStats
      } yield TaskCounts(tasks)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while calculating task counts for app [${app.id}]", e)
    }

    lazy val taskStatsFuture: Future[TaskStatsByVersion] = {
      log.debug(s"calculating task stats for app [${app.id}]")
      for {
        tasks <- tasksForStats
      } yield TaskStatsByVersion(app.versionInfo, tasks)
    }

    lazy val enrichedTasksFuture: Future[Seq[EnrichedTask]] = {
      log.debug(s"assembling rich tasks for app [${app.id}]")
      def statusesToEnrichedTasks(instances: Seq[Instance], statuses: Map[Instance.Id, collection.Seq[Health]]): Seq[EnrichedTask] = {
        instances.map { instance =>
          EnrichedTask(app.id, instance.appTask, instance.agentInfo, statuses.getOrElse(instance.instanceId, Seq.empty[Health]).to[Seq])
        }
      }

      for {
        instances: Seq[Instance] <- instancesFuture
        statuses <- healthByInstanceIdFuture
      } yield statusesToEnrichedTasks(instances, statuses)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while assembling rich tasks for app [${app.id}]", e)
    }

    lazy val maybeLastTaskFailureFuture: Future[Option[TaskFailure]] = {
      log.debug(s"retrieving last task failure for app [${app.id}]")
      taskFailureRepository.get(app.id)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while retrieving last task failure for app [${app.id}]", e)
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  def podStatus(podDef: PodDefinition): Future[PodStatus] =
    async { // linter:ignore UnnecessaryElseBranch
      val now = clock.now().toOffsetDateTime
      val instances = await(instancesByRunSpecFuture).specInstances(podDef.id)
      val specByVersion: Map[Timestamp, Option[PodDefinition]] = await(Future.sequence(
        // TODO(jdef) if repositories ever support a bulk-load interface, use it here
        instances.map(_.runSpecVersion).distinct.map { version =>
          groupManager.podVersion(podDef.id, version.toOffsetDateTime).map(version -> _)
        }
      )).toMap
      val instanceStatus = instances.flatMap { inst => podInstanceStatus(inst)(specByVersion.apply) }
      val statusSince = if (instanceStatus.isEmpty) now else instanceStatus.map(_.statusSince).max
      val state = await(podState(podDef.instances, instanceStatus, isPodTerminating(podDef.id)))

      // TODO(jdef) pods need termination history
      PodStatus(
        id = podDef.id.toString,
        spec = Raml.toRaml(podDef),
        instances = instanceStatus,
        status = state,
        statusSince = statusSince,
        lastUpdated = now,
        lastChanged = statusSince
      )
    }

  def podInstanceStatus(instance: Instance)(f: Timestamp => Option[PodDefinition]): Option[PodInstanceStatus] = {
    val maybePodSpec: Option[PodDefinition] = f(instance.runSpecVersion)

    if (maybePodSpec.isEmpty)
      log.warn(s"failed to generate pod instance status for instance ${instance.instanceId}, " +
        s"pod version ${instance.runSpecVersion} failed to load from persistent store")

    maybePodSpec.map { pod => Raml.toRaml(pod -> instance) }
  }

  protected def isPodTerminating(id: PathId): Future[Boolean] =
    runningDeployments.map { infos =>
      infos.exists(_.plan.deletedPods.contains(id))
    }

  @SuppressWarnings(Array("all")) // async/await
  protected def podState(
    expectedInstanceCount: Integer,
    instanceStatus: Seq[PodInstanceStatus],
    isPodTerminating: Future[Boolean]): Future[PodState] =

    async { // linter:ignore UnnecessaryElseBranch
      val terminal = await(isPodTerminating)
      val state = if (terminal) {
        PodState.Terminal
      } else if (instanceStatus.count(_.status == PodInstanceState.Stable) >= expectedInstanceCount) {
        // TODO(jdef) add an "oversized" condition, or related message of num-current-instances > expected?
        PodState.Stable
      } else {
        PodState.Degraded
      }
      state
    }
}

object AppInfoBaseData {
  private val log = LoggerFactory.getLogger(getClass)
}
