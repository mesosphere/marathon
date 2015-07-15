package mesosphere.marathon.integration.setup

import java.io.File
import java.util.Date

import akka.actor.ActorSystem
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import mesosphere.marathon.api.v2.json.{ V2AppDefinition, V2AppDefinitionWrapper, V2AppUpdate, V2Group, V2GroupUpdate }
import mesosphere.marathon.event.http.EventSubscribers
import mesosphere.marathon.event.{ Subscribe, Unsubscribe }
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp }
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http._

import scala.collection.immutable.Seq
import scala.concurrent.Await.result
import scala.concurrent.duration._

/**
  * GET /apps will deliver something like Apps instead of List[App]
  * Needed for dumb jackson.
  */
case class ITListAppsResult(apps: Seq[V2AppDefinition])
case class ITAppVersions(versions: Seq[Timestamp])
case class ITListTasks(tasks: Seq[ITEnrichedTask])
@JsonIgnoreProperties(ignoreUnknown = true)
case class ITDeploymentPlan(version: String, deploymentId: String)
case class ITHealthCheckResult(taskId: String, firstSuccess: Date, lastSuccess: Date, lastFailure: Date, consecutiveFailures: Int, alive: Boolean)
case class ITDeploymentResult(version: Timestamp, deploymentId: String)
@JsonIgnoreProperties(ignoreUnknown = true)
case class ITEnrichedTask(appId: String, id: String, host: String, ports: Seq[Integer], startedAt: Date, stagedAt: Date, version: String /*, healthCheckResults:Seq[ITHealthCheckResult]*/ )
case class ITLeaderResult(leader: String)

case class ITListDeployments(deployments: Seq[ITDeployment])

case class ITQueueDelay(timeLeftSeconds: Int, overdue: Boolean)
case class ITQueueItem(app: V2AppDefinition, count: Int, delay: ITQueueDelay)
case class ITTaskQueue(queue: List[ITQueueItem])

@JsonIgnoreProperties(ignoreUnknown = true)
case class ITDeployment(id: String, affectedApps: Seq[String])

/**
  * The MarathonFacade offers the REST API of a remote marathon instance
  * with all local domain objects.
  * @param url the url of the remote marathon instance
  */
class MarathonFacade(url: String, baseGroup: PathId, waitTime: Duration = 30.seconds)(implicit val system: ActorSystem) extends JacksonSprayMarshaller {
  import mesosphere.util.ThreadPoolContext.context

  require(baseGroup.absolute)

  implicit val v2appDefWrapperMarshaller = marshaller[V2AppDefinitionWrapper]
  implicit val v2appDefMarshaller = marshaller[V2AppDefinition]
  implicit val deploymentPlanMarshaller = marshaller[ITDeploymentPlan]
  implicit val appUpdateMarshaller = marshaller[V2AppUpdate]
  implicit val v2groupMarshaller = marshaller[V2Group]
  implicit val groupUpdateMarshaller = marshaller[V2GroupUpdate]
  implicit val versionMarshaller = marshaller[ITDeploymentResult]

  def isInBaseGroup(pathId: PathId): Boolean = {
    pathId.path.startsWith(baseGroup.path)
  }

  def requireInBaseGroup(pathId: PathId): Unit = {
    require(isInBaseGroup(pathId), s"pathId $pathId must be in baseGroup ($baseGroup)")
  }

  //app resource ----------------------------------------------

  def listAppsInBaseGroup: RestResult[List[V2AppDefinition]] = {
    val pipeline = sendReceive ~> read[ITListAppsResult]
    val res = result(pipeline(Get(s"$url/v2/apps")), waitTime)
    res.map(_.apps.toList.filter(app => isInBaseGroup(app.id)))
  }

  def app(id: PathId): RestResult[V2AppDefinitionWrapper] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[V2AppDefinitionWrapper]
    val getUrl: String = s"$url/v2/apps$id"
    LoggerFactory.getLogger(getClass).info(s"get url = $getUrl")
    result(pipeline(Get(getUrl)), waitTime)
  }

  def createAppV2(app: V2AppDefinition): RestResult[V2AppDefinition] = {
    requireInBaseGroup(app.id)
    val pipeline = sendReceive ~> read[V2AppDefinition]
    result(pipeline(Post(s"$url/v2/apps", app)), waitTime)
  }

  def deleteApp(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Delete(s"$url/v2/apps$id?force=$force")), waitTime)
  }

  def updateApp(id: PathId, app: V2AppUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    val putUrl: String = s"$url/v2/apps$id?force=$force"
    LoggerFactory.getLogger(getClass).info(s"put url = $putUrl")

    result(pipeline(Put(putUrl, app)), waitTime)
  }

  def restartApp(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Post(s"$url/v2/apps$id/restart?force=$force")), waitTime)
  }

  def listAppVersions(id: PathId): RestResult[ITAppVersions] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[ITAppVersions]
    result(pipeline(Get(s"$url/v2/apps$id/versions")), waitTime)
  }

  def appVersion(id: PathId, version: Timestamp): RestResult[AppDefinition] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[AppDefinition]
    result(pipeline(Get(s"$url/v2/apps$id/versions/$version")), waitTime)
  }

  //apps tasks resource --------------------------------------

  def tasks(appId: PathId): RestResult[List[ITEnrichedTask]] = {
    requireInBaseGroup(appId)
    val pipeline = addHeader("Accept", "application/json") ~> sendReceive ~> read[ITListTasks]
    val res = result(pipeline(Get(s"$url/v2/apps$appId/tasks")), waitTime)
    res.map(_.tasks.toList)
  }

  def killAllTasks(appId: PathId, scale: Boolean = false): RestResult[ITListTasks] = {
    requireInBaseGroup(appId)
    val pipeline = sendReceive ~> read[ITListTasks]
    result(pipeline(Delete(s"$url/v2/apps$appId/tasks?scale=$scale")), waitTime)
  }

  def killAllTasksAndScale(appId: PathId): RestResult[ITDeploymentPlan] = {
    requireInBaseGroup(appId)
    val pipeline = sendReceive ~> read[ITDeploymentPlan]
    result(pipeline(Delete(s"$url/v2/apps$appId/tasks?scale=true")), waitTime)
  }

  def killTask(appId: PathId, taskId: String, scale: Boolean = false): RestResult[HttpResponse] = {
    requireInBaseGroup(appId)
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/apps$appId/tasks/$taskId?scale=$scale")), waitTime)
  }

  //group resource -------------------------------------------

  def listGroupsInBaseGroup: RestResult[Set[Group]] = {
    val pipeline = sendReceive ~> read[Group]
    val root = result(pipeline(Get(s"$url/v2/groups")), waitTime)
    root.map(_.groups.filter(group => isInBaseGroup(group.id)))
  }

  def listGroupVersions(id: PathId): RestResult[List[String]] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[Array[String]] ~> toList[String]
    result(pipeline(Get(s"$url/v2/groups$id/versions")), waitTime)
  }

  def group(id: PathId): RestResult[Group] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[Group]
    result(pipeline(Get(s"$url/v2/groups$id")), waitTime)
  }

  def createGroup(group: V2GroupUpdate): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(group.groupId)
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Post(s"$url/v2/groups", group)), waitTime)
  }

  def deleteGroup(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Delete(s"$url/v2/groups$id?force=$force")), waitTime)
  }

  def deleteRoot(force: Boolean): RestResult[ITDeploymentResult] = {
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Delete(s"$url/v2/groups?force=$force")), waitTime)
  }

  def updateGroup(id: PathId, group: V2GroupUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = sendReceive ~> read[ITDeploymentResult]
    result(pipeline(Put(s"$url/v2/groups$id?force=$force", group)), waitTime)
  }

  def rollbackGroup(groupId: PathId, version: Timestamp, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(groupId)
    updateGroup(groupId, V2GroupUpdate(None, version = Some(version)), force)
  }

  //deployment resource ------

  def listDeploymentsForBaseGroup(): RestResult[List[ITDeployment]] = {
    val pipeline = sendReceive ~> read[Array[ITDeployment]] ~> toList[ITDeployment]
    result(pipeline(Get(s"$url/v2/deployments")), waitTime).map { deployments =>
      deployments.filter { deployment =>
        deployment.affectedApps.map(PathId(_)).exists(id => isInBaseGroup(id))
      }
    }
  }

  def deleteDeployment(id: String, force: Boolean = false): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/deployments/$id?force=$force")), waitTime)
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

  //metrics ---------------------------------------------

  def metrics(): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Get(s"$url/metrics")), waitTime)
  }

  //artifacts ---------------------------------------------
  def uploadArtifact(path: String, file: File): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    val payload = MultipartFormData(Seq(BodyPart(file, "file")))
    result(pipeline(Post(s"$url/v2/artifacts$path", payload)), waitTime)
  }

  def getArtifact(path: String): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Get(s"$url/v2/artifacts$path")), waitTime)
  }

  def deleteArtifact(path: String): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/artifacts$path")), waitTime)
  }

  //leader ----------------------------------------------
  def leader(): RestResult[ITLeaderResult] = {
    val pipeline = sendReceive ~> read[ITLeaderResult]
    result(pipeline(Get(s"$url/v2/leader")), waitTime)
  }

  def abdicate(): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/leader")), waitTime)
  }

  //info --------------------------------------------------
  def info: RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Get(s"$url/v2/info")), waitTime)
  }

  //task queue ------------------------------------------
  def taskQueue(): RestResult[ITTaskQueue] = {
    val pipeline = sendReceive ~> read[ITTaskQueue]
    result(pipeline(Get(s"$url/v2/queue")), waitTime)
  }
}
