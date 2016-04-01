package mesosphere.marathon.core.appinfo.impl

import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.core.appinfo.{ AppInfo, EnrichedTask, TaskCounts, TaskStatsByVersion }
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.readiness.ReadinessCheckResult
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.health.{ Health, HealthCheckManager }
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentManager.DeploymentStepInfo
import mesosphere.marathon.upgrade.DeploymentPlan
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.util.control.NonFatal

class AppInfoBaseData(
    clock: Clock,
    taskTracker: TaskTracker,
    healthCheckManager: HealthCheckManager,
    marathonSchedulerService: MarathonSchedulerService,
    taskFailureRepository: TaskFailureRepository) {
  import AppInfoBaseData.log

  import scala.concurrent.ExecutionContext.Implicits.global

  if (log.isDebugEnabled) log.debug(s"new AppInfoBaseData $this")

  lazy val runningDeployments: Future[Seq[DeploymentStepInfo]] = marathonSchedulerService.listRunningDeployments()

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
        deploymentPlan.affectedApplicationIds.foldLeft(result) { (result, appId) =>
          val newEl = appId -> (result(appId) :+ deploymentPlan)
          result + newEl
        }
      }
      deploymentsByAppId
        .mapValues(_.map(deploymentPlan => Identifiable(deploymentPlan.id)))
        .withDefaultValue(Seq.empty)
    }
  }

  lazy val tasksByAppFuture: Future[TaskTracker.TasksByApp] = {
    log.debug("Retrieve tasks")
    taskTracker.tasksByApp()
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

    lazy val tasksFuture: Future[Iterable[Task]] = tasksByAppFuture.map(_.appTasks(app.id))

    lazy val healthCountsFuture: Future[Map[Task.Id, Seq[Health]]] = {
      log.debug(s"retrieving health counts for app [${app.id}]")
      healthCheckManager.statuses(app.id)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while retrieving health counts for app [${app.id}]", e)
    }

    lazy val tasksForStats: Future[Iterable[TaskForStatistics]] = {
      for {
        tasks <- tasksFuture
        healthCounts <- healthCountsFuture
      } yield TaskForStatistics.forTasks(now, tasks, healthCounts)
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
      def statusesToEnrichedTasks(
        tasksById: Map[Task.Id, Task],
        statuses: Map[Task.Id, collection.Seq[Health]]): Seq[EnrichedTask] = {
        for {
          (taskId, healthResults) <- statuses.to[Seq]
          task <- tasksById.get(taskId)
        } yield EnrichedTask(app.id, task, healthResults)
      }

      log.debug(s"assembling rich tasks for app [${app.id}]")

      val tasksByIdFuture = tasksByAppFuture.map(_.appTasksMap.get(app.id).map(_.taskStateMap).getOrElse(Map.empty))
      val healthStatusesFutures = healthCheckManager.statuses(app.id)
      for {
        tasksById <- tasksByIdFuture
        statuses <- healthStatusesFutures
      } yield statusesToEnrichedTasks(tasksById, statuses)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while assembling rich tasks for app [${app.id}]", e)
    }

    lazy val maybeLastTaskFailureFuture: Future[Option[TaskFailure]] = {
      log.debug(s"retrieving last task failure for app [${app.id}]")
      taskFailureRepository.current(app.id)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while retrieving last task failure for app [${app.id}]", e)
    }
  }
}

object AppInfoBaseData {
  private val log = LoggerFactory.getLogger(getClass)
}
