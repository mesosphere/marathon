package mesosphere.marathon
package core.appinfo.impl

import java.time.Clock

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.appinfo.{AppInfo, EnrichedTask, TaskCounts, TaskStatsByVersion}
import mesosphere.marathon.core.deployment.{DeploymentPlan, DeploymentStepInfo}
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{Health, HealthCheckManager}
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.ReadinessCheckResult
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.raml.{ContainerTerminationHistory, PodInstanceState, PodInstanceStatus, PodState, PodStatus, Raml}
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.TaskFailureRepository

import scala.async.Async.{async, await}
import scala.collection.immutable.{Map, Seq}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

// TODO(jdef) pods rename this to something like ResourceInfoBaseData
class AppInfoBaseData(
    clock: Clock,
    instanceTracker: InstanceTracker,
    healthCheckManager: HealthCheckManager,
    deploymentService: DeploymentService,
    taskFailureRepository: TaskFailureRepository,
    groupManager: GroupManager)(implicit ec: ExecutionContext) extends StrictLogging {

  logger.debug(s"new AppInfoBaseData $this")

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
    logger.debug("Retrieving running deployments")

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
    logger.debug("Retrieve tasks")
    instanceTracker.instancesBySpec()
  }

  def appInfoFuture(app: AppDefinition, embeds: Set[AppInfo.Embed]): Future[AppInfo] = async {
    val appData = new AppData(app)

    val taskCountsOpt: Option[TaskCounts] = if (embeds.contains(AppInfo.Embed.Counts)) Some(await(appData.taskCountsFuture)) else None
    val readinessChecksByAppOpt: Option[Map[PathId, Seq[ReadinessCheckResult]]] = if (embeds.contains(AppInfo.Embed.Readiness)) Some(await(readinessChecksByAppFuture)) else None
    val runningDeploymentsByAppOpt: Option[Map[PathId, Seq[Identifiable]]] = if (embeds.contains(AppInfo.Embed.Deployments)) Some(await(runningDeploymentsByAppFuture)) else None
    val lastTaskFailureOpt: Option[TaskFailure] = if (embeds.contains(AppInfo.Embed.LastTaskFailure)) await(appData.maybeLastTaskFailureFuture) else None
    val enrichedTasksOpt: Option[Seq[EnrichedTask]] = if (embeds.contains(AppInfo.Embed.Tasks)) Some(await(appData.enrichedTasksFuture)) else None
    val taskStatsOpt: Option[TaskStatsByVersion] = if (embeds.contains(AppInfo.Embed.TaskStats)) Some(await(appData.taskStatsFuture)) else None

    val appInfo = AppInfo(
      app = app,
      maybeTasks = enrichedTasksOpt,
      maybeCounts = taskCountsOpt,
      maybeDeployments = runningDeploymentsByAppOpt.map(_.apply(app.id)),
      maybeReadinessCheckResults = readinessChecksByAppOpt.map(_.apply(app.id)),
      maybeLastTaskFailure = lastTaskFailureOpt,
      maybeTaskStats = taskStatsOpt
    )
    appInfo
  }

  /**
    * Contains app-sepcific data that we need to retrieved.
    *
    * All data is lazy such that only data that is actually needed for the requested embedded information
    * gets retrieved.
    */
  private[this] class AppData(app: AppDefinition) {
    lazy val now: Timestamp = clock.now()

    lazy val instancesFuture: Future[Vector[Instance]] = instancesByRunSpecFuture
      .map(_.specInstances(app.id).toVector)

    lazy val healthByInstanceIdFuture: Future[Map[Instance.Id, Seq[Health]]] = {
      logger.debug(s"retrieving health counts for app [${app.id}]")
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
      logger.debug(s"calculating task counts for app [${app.id}]")
      for {
        tasks <- tasksForStats
      } yield TaskCounts(tasks)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while calculating task counts for app [${app.id}]", e)
    }

    lazy val taskStatsFuture: Future[TaskStatsByVersion] = {
      logger.debug(s"calculating task stats for app [${app.id}]")
      for {
        tasks <- tasksForStats
      } yield TaskStatsByVersion(app.versionInfo, tasks)
    }

    lazy val enrichedTasksFuture: Future[Seq[EnrichedTask]] = {
      logger.debug(s"assembling rich tasks for app [${app.id}]")
      def statusesToEnrichedTasks(instances: Seq[Instance], statuses: Map[Instance.Id, collection.Seq[Health]]): Seq[EnrichedTask] = {
        instances.flatMap { instance =>
          EnrichedTask.singleFromInstance(instance, healthCheckResults = statuses.getOrElse(instance.instanceId, Nil).to[Seq])
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
      logger.debug(s"retrieving last task failure for app [${app.id}]")
      taskFailureRepository.get(app.id)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while retrieving last task failure for app [${app.id}]", e)
    }
  }

  def podStatus(podDef: PodDefinition): Future[PodStatus] = async { // linter:ignore UnnecessaryElseBranch
    val now = clock.now().toOffsetDateTime
    val instances = await(instancesByRunSpecFuture).specInstances(podDef.id)
    val specByVersion: Map[Timestamp, Option[PodDefinition]] = await(Future.sequence(
      // TODO(jdef) if repositories ever support a bulk-load interface, use it here
      instances.map(_.runSpecVersion).distinct.map { version =>
        groupManager.podVersion(podDef.id, version.toOffsetDateTime).map(version -> _)
      }
    )).toMap
    val instanceStatus = instances
      .filter(!_.isScheduled)
      .flatMap { inst => podInstanceStatus(inst)(specByVersion.apply) }
    val statusSince = if (instanceStatus.isEmpty) now else instanceStatus.map(_.statusSince).max
    val state = await(podState(podDef.instances, instanceStatus, isPodTerminating(podDef.id)))

    val taskFailureOpt: Option[TaskFailure] = await {
      taskFailureRepository
        .get(podDef.id)
        .recover { case NonFatal(e) => None }
    }
    val failedInstanceBundle: Option[(Instance, Task, TaskFailure)] = taskFailureOpt.flatMap { taskFailure =>
      val failedTaskId = core.task.Task.Id.parse(taskFailure.taskId)
      instances.collectFirst {
        case instance if instance.tasksMap.contains(failedTaskId) =>
          (instance, instance.tasksMap(failedTaskId), taskFailure)
      }
    }

    import mesosphere.mesos.protos.Implicits.taskStateToCaseClass

    val terminationHistory = failedInstanceBundle.map {
      case (instance, task, taskFailure) =>
        raml.TerminationHistory(
          instanceID = instance.instanceId.idString,
          startedAt = task.status.startedAt.getOrElse {
            // startedAt will only be set when a task turns running. In order to not break
            // potential expectations, the property stays mandatory and is populated with the time
            // when the now terminal task was initially staged instead.
            logger.warn(s"${task.taskId} has no startedAt. Falling back to stagedAt.")
            task.status.stagedAt
          }.toOffsetDateTime,
          terminatedAt = taskFailure.timestamp.toOffsetDateTime,
          message = Some(taskFailure.message),
          containers = List(
            ContainerTerminationHistory(
              containerId = task.taskId.idString,
              lastKnownState = Some(taskStateToCaseClass(taskFailure.state).toString)
            )
          )
        )
    }.toList

    PodStatus(
      id = podDef.id.toString,
      spec = Raml.toRaml(podDef),
      instances = instanceStatus,
      status = state,
      statusSince = statusSince,
      lastUpdated = now,
      lastChanged = statusSince,
      terminationHistory = terminationHistory
    )
  }

  def podInstanceStatus(instance: Instance)(f: Timestamp => Option[PodDefinition]): Option[PodInstanceStatus] = {
    val maybePodSpec: Option[PodDefinition] = f(instance.runSpecVersion)

    if (maybePodSpec.isEmpty)
      logger.warn(s"failed to generate pod instance status for instance ${instance.instanceId}, " +
        s"pod version ${instance.runSpecVersion} failed to load from persistent store")

    maybePodSpec.map { pod => Raml.toRaml(pod -> instance) }
  }

  protected def isPodTerminating(id: PathId): Future[Boolean] =
    runningDeployments.map { infos =>
      infos.exists(_.plan.deletedPods.contains(id))
    }

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
