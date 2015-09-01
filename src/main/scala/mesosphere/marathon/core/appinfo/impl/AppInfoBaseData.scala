package mesosphere.marathon.core.appinfo.impl

import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.appinfo.{ TaskCounts, AppInfo, EnrichedTask }
import mesosphere.marathon.health.{ Health, HealthCheckManager, HealthCounts }
import mesosphere.marathon.state.{ TaskFailure, TaskFailureRepository, Identifiable, PathId, AppDefinition }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.DeploymentPlan
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.util.control.NonFatal

class AppInfoBaseData(
    taskTracker: TaskTracker,
    healthCheckManager: HealthCheckManager,
    marathonSchedulerService: MarathonSchedulerService,
    taskFailureRepository: TaskFailureRepository) {
  import AppInfoBaseData.log
  import scala.concurrent.ExecutionContext.Implicits.global

  lazy val runningDeploymentsByAppFuture: Future[Map[PathId, Seq[Identifiable]]] = {
    log.debug("Retrieving running deployments")

    val allRunningDeploymentsFuture: Future[Seq[DeploymentPlan]] =
      for {
        stepInfos <- marathonSchedulerService.listRunningDeployments()
      } yield stepInfos.map(_.plan)

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

  def appInfoFuture(app: AppDefinition, embed: Set[AppInfo.Embed]): Future[AppInfo] = {
    val appData = new AppData(app)
    embed.foldLeft(Future.successful(AppInfo(app))) { (infoFuture, embed) =>
      infoFuture.flatMap { info =>
        embed match {
          case AppInfo.Embed.Counts =>
            appData.taskCountsFuture.map(counts => info.copy(maybeCounts = Some(counts)))
          case AppInfo.Embed.Deployments =>
            runningDeploymentsByAppFuture.map(deployments => info.copy(maybeDeployments = Some(deployments(app.id))))
          case AppInfo.Embed.LastTaskFailure =>
            appData.maybeLastTaskFailureFuture.map { maybeLastTaskFailure =>
              info.copy(maybeLastTaskFailure = maybeLastTaskFailure)
            }
          case AppInfo.Embed.Tasks =>
            appData.enrichedTasksFuture.map(tasks => info.copy(maybeTasks = Some(tasks)))
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
    lazy val tasks: Set[MarathonTask] = {
      log.debug(s"retrieving running tasks for app [${app.id}]")
      taskTracker.get(app.id)
    }

    lazy val tasksFuture: Future[Set[MarathonTask]] = Future.successful(tasks)

    lazy val healthCountsFuture: Future[HealthCounts] = {
      log.debug(s"retrieving health counts for app [${app.id}]")
      healthCheckManager.healthCounts(app.id)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while retrieving health counts for app [${app.id}]", e)
    }

    lazy val taskCountsFuture = {
      log.debug(s"calculating task counts for app [${app.id}]")
      for {
        tasks <- tasksFuture
        healthCounts <- healthCountsFuture
      } yield TaskCounts(tasks, healthCounts)
    }.recover {
      case NonFatal(e) => throw new RuntimeException(s"while calculating task counts for app [${app.id}]", e)
    }

    lazy val enrichedTasksFuture: Future[Seq[EnrichedTask]] = {
      def statusesToEnrichedTasks(
        tasksById: Map[String, MarathonTask],
        statuses: Map[String, collection.Seq[Health]]): Seq[EnrichedTask] = {
        for {
          (taskId, healthResults) <- statuses.to[Seq]
          task <- tasksById.get(taskId)
        } yield EnrichedTask(app.id, task, healthResults)
      }

      log.debug(s"assembling rich tasks for app [${app.id}]")
      val tasksById: Map[String, MarathonTask] = tasks.map(task => task.getId -> task).toMap
      healthCheckManager.statuses(app.id).map(statuses => statusesToEnrichedTasks(tasksById, statuses))
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
