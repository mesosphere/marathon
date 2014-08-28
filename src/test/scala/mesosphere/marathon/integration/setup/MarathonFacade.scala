package mesosphere.marathon.integration.setup

import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.concurrent.Await.result
import spray.client.pipelining._
import spray.http.HttpResponse
import mesosphere.marathon.state.{ Timestamp, PathId, Group }
import mesosphere.marathon.event.http.EventSubscribers
import mesosphere.marathon.event.{ Unsubscribe, Subscribe }
import mesosphere.marathon.state.AppDefinition
import java.util.Date
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import mesosphere.marathon.api.v2.GroupUpdate

/**
  * GET /apps will deliver something like Apps instead of List[App]
  * Needed for dumb jackson.
  */
case class ListAppsResult(apps: Seq[AppDefinition])
case class ListTasks(tasks: Seq[ITEnrichedTask])
case class ITHealthCheckResult(taskId: String, firstSuccess: Date, lastSuccess: Date, lastFailure: Date, consecutiveFailures: Int, alive: Boolean)
case class ITDeploymentResult(version: String, deploymentId: String)
@JsonIgnoreProperties(ignoreUnknown = true)
case class ITEnrichedTask(appId: String, id: String, host: String, ports: Seq[Integer], startedAt: Date, stagedAt: Date, version: String /*, healthCheckResults:Seq[ITHealthCheckResult]*/ )
/**
  * The MarathonFacade offers the REST API of a remote marathon instance
  * with all local domain objects.
  * @param url the url of the remote marathon instance
  */
class MarathonFacade(url: String, waitTime: Duration = 30.seconds) extends JacksonSprayMarshaller {
  import mesosphere.util.ThreadPoolContext.context

  implicit val system = ActorSystem()
  implicit val appDefMarshaller = marshaller[AppDefinition]
  implicit val groupMarshaller = marshaller[Group]
  implicit val groupUpdateMarshaller = marshaller[GroupUpdate]
  implicit val versionMarshaller = marshaller[ITDeploymentResult]

  //app resource ----------------------------------------------

  def listApps: RestResult[List[AppDefinition]] = {
    val pipeline = sendReceive ~> read[ListAppsResult]
    val res = result(pipeline(Get(s"$url/v2/apps")), waitTime)
    RestResult(res.value.apps.toList, res.code)
  }

  def app(id: PathId): RestResult[AppDefinition] = {
    val pipeline = sendReceive ~> read[AppDefinition]
    result(pipeline(Get(s"$url/v2/apps$id")), waitTime)
  }

  def createApp(app: AppDefinition): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Post(s"$url/v2/apps", app)), waitTime)
  }

  def deleteApp(id: PathId): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/apps$id")), waitTime)
  }

  def updateApp(app: AppDefinition): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Put(s"$url/v2/apps${app.id}", app)), waitTime)
  }

  //apps tasks resource --------------------------------------

  def tasks(appId: PathId): RestResult[List[ITEnrichedTask]] = {
    val pipeline = sendReceive ~> read[ListTasks]
    val res = result(pipeline(Get(s"$url/v2/apps$appId/tasks")), waitTime)
    RestResult(res.value.tasks.toList, res.code)
  }

  //group resource -------------------------------------------

  def listGroups: RestResult[Set[Group]] = {
    val pipeline = sendReceive ~> read[Group]
    val root = result(pipeline(Get(s"$url/v2/groups")), waitTime)
    RestResult(root.value.groups, root.code)
  }

  def listGroupVersions(id: PathId): RestResult[List[String]] = {
    val pipeline = sendReceive ~> read[Array[String]] ~> toList[String]
    result(pipeline(Get(s"$url/v2/groups$id/versions")), waitTime)
  }

  def group(id: PathId): RestResult[Group] = {
    val pipeline = sendReceive ~> read[Group]
    result(pipeline(Get(s"$url/v2/groups$id")), waitTime)
  }

  def createGroup(group: GroupUpdate): RestResult[ITDeploymentResult] = {
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Post(s"$url/v2/groups", group)), waitTime)
  }

  def deleteGroup(id: PathId): RestResult[ITDeploymentResult] = {
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Delete(s"$url/v2/groups$id")), waitTime)
  }

  def deleteRoot(force: Boolean): RestResult[ITDeploymentResult] = {
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Delete(s"$url/v2/groups?force=$force")), waitTime)
  }

  def updateGroup(id: PathId, group: GroupUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Put(s"$url/v2/groups$id?force=$force", group)), waitTime)
  }

  def rollbackGroup(groupId: PathId, version: String, force: Boolean = false): RestResult[ITDeploymentResult] = {
    updateGroup(groupId, GroupUpdate(None, version = Some(Timestamp(version))), force)
  }

  //event resource ---------------------------------------------

  def listSubscribers: RestResult[EventSubscribers] = {
    val pipeline = sendReceive ~> read[EventSubscribers]
    result(pipeline(Get(s"$url/v2/eventSubscriptions")), waitTime)
  }

  def subscribe(callbackUrl: String): RestResult[Subscribe] = {
    val pipeline = sendReceive ~> read[Subscribe]
    result(pipeline(Post(s"$url/v2/eventSubscriptions?callbackUrl=$callbackUrl")), waitTime)
  }

  def unsubscribe(callbackUrl: String): RestResult[Unsubscribe] = {
    val pipeline = sendReceive ~> read[Unsubscribe]
    result(pipeline(Delete(s"$url/v2/eventSubscriptions?callbackUrl=$callbackUrl")), waitTime)
  }

}
