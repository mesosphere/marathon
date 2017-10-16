package mesosphere.marathon
package api.akkahttp.v2

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.akkahttp.Controller
import mesosphere.marathon.api.akkahttp.Directives.pathEndOrSingleSlash
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{ Health, HealthCheckManager }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.plugin.auth.{ Authenticator, _ }
import play.api.libs.json.Json
import mesosphere.marathon.raml.{ AnyToRaml, EnrichedTasksList, Writes }
import mesosphere.marathon.state.PathId

import scala.async.Async._
import scala.concurrent.{ ExecutionContext, Future }

class TasksController(instanceTracker: InstanceTracker, groupManager: GroupManager, healthCheckManager: HealthCheckManager)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: Authenticator,
    val authorizer: Authorizer
) extends Controller with StrictLogging {

  import mesosphere.marathon.api.akkahttp.Directives._
  import mesosphere.marathon.api.akkahttp.EntityMarshallers._
  import mesosphere.marathon.raml.EnrichedTaskConversion._

  override val route = {
    get {
      pathEndOrSingleSlash {
        listTasks()
      }
    }
  }

  private def listTasks(): Route = {
    authenticated.apply { implicit identity =>
      authorized(ViewResource, AuthorizedResource.SystemConfig).apply {
        parameters("status".?, "status[]".as[String].*) { (statusParameter, statusParameters) =>
          val statuses = statusParameter.fold(Seq.empty[String])(s => Seq(s)) ++ statusParameters
          onSuccess(enrichedTasks(statuses)) { tasks =>
            complete(TasksList(tasks).toRaml)
          }
        }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def enrichedTasks(statuses: Seq[String]): Future[Seq[EnrichedTask]] = async {
    val conditionSet: Set[Condition] = statuses.flatMap(toCondition)(collection.breakOut)

    val instancesBySpec = await(instanceTracker.instancesBySpec)

    val instances: Iterable[(PathId, Instance)] = instancesBySpec.instancesMap.values.flatMap { appTasks =>
      appTasks.instances.map(i => appTasks.specId -> i)
    }
    val appIds: Set[PathId] = instancesBySpec.allSpecIdsWithInstances

    val appToPorts: Map[PathId, Seq[Int]] = groupManager.apps(appIds).map {
      case (appId, app) => appId -> app.map(_.servicePorts).getOrElse(Nil)
    }

    val instancesHealth = await(
      Future.sequence(appIds.map { appId =>
        healthCheckManager.statuses(appId)
      })).foldLeft(Map[Id, Seq[Health]]())(_ ++ _)

    val enrichedTasks: Iterable[Iterable[EnrichedTask]] = for {
      (appId, instance) <- instances
      if conditionSet.isEmpty || conditionSet(instance.state.condition)
      tasks = instance.tasksMap.values
    } yield {
      tasks.map { task =>
        EnrichedTask(
          appId,
          task,
          instance.agentInfo,
          instancesHealth.getOrElse(instance.instanceId, Nil),
          appToPorts.getOrElse(appId, Nil)
        )
      }
    }
    Seq(enrichedTasks.flatten.toSeq: _*)
  }

  private def toCondition(state: String): Option[Condition] = state.toLowerCase match {
    case "running" => Some(Condition.Running)
    case "staging" => Some(Condition.Staging)
    case _ => None
  }

  case class TasksList(tasks: Seq[EnrichedTask])
  implicit val tasksListWrite: Writes[TasksList, EnrichedTasksList] = Writes { tasksList =>
    EnrichedTasksList(tasksList.tasks.map(_.toRaml))
  }
}
