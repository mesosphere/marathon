package mesosphere.marathon
package integration.facades

import java.util.Date

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.{ Delete, Get, Patch, Post, Put }
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{ MediaType, _ }
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.unmarshalling.{ Unmarshal => AkkaUnmarshal }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.typesafe.scalalogging.StrictLogging
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import de.heikoseeberger.akkasse.{ EventStreamUnmarshalling, ServerSentEvent }
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.integration.setup.{ AkkaHttpResponse, RestResult }
import mesosphere.marathon.raml.{ App, AppUpdate, GroupInfo, GroupUpdate, Pod, PodConversion, PodInstanceStatus, PodStatus, Raml }
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.util.Retry
import org.slf4j.LoggerFactory
import play.api.libs.functional.syntax._
import play.api.libs.json.JsArray

import scala.collection.immutable.Seq
import scala.concurrent.Await.result
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * GET /apps will deliver something like Apps instead of List[App]
  * Needed for dumb jackson.
  */
case class ITAppDefinition(app: App)
case class ITListAppsResult(apps: Seq[App])
case class ITAppVersions(versions: Seq[Timestamp])
case class ITListTasks(tasks: Seq[ITEnrichedTask])
case class ITDeploymentPlan(version: String, deploymentId: String)
case class ITHealthCheckResult(taskId: String, firstSuccess: Date, lastSuccess: Date, lastFailure: Date, consecutiveFailures: Int, alive: Boolean)
case class ITDeploymentResult(version: Timestamp, deploymentId: String)
case class ITEnrichedTask(
    appId: String,
    id: String,
    host: String,
    ports: Option[Seq[Int]],
    slaveId: Option[String],
    startedAt: Option[Date],
    stagedAt: Option[Date],
    state: String,
    version: Option[String]) {

  def launched: Boolean = startedAt.nonEmpty
  def suspended: Boolean = startedAt.isEmpty
}
case class ITLeaderResult(leader: String) {
  val port: Integer = leader.split(":")(1).toInt
}

case class ITListDeployments(deployments: Seq[ITDeployment])

case class ITQueueDelay(timeLeftSeconds: Int, overdue: Boolean)
case class ITQueueItem(app: App, count: Int, delay: ITQueueDelay)
case class ITLaunchQueue(queue: List[ITQueueItem])

case class ITDeployment(id: String, affectedApps: Seq[String], affectedPods: Seq[String])

sealed trait ITSSEEvent
/** Used to signal that the SSE stream is connected */
case object ITConnected extends ITSSEEvent

/** models each SSE published event */
case class ITEvent(eventType: String, info: Map[String, Any]) extends ITSSEEvent

/**
  * The MarathonFacade offers the REST API of a remote marathon instance
  * with all local domain objects.
  *
  * @param url the url of the remote marathon instance
  */
class MarathonFacade(
  val url: String, baseGroup: PathId, implicit val waitTime: FiniteDuration = 30.seconds)(
  implicit
  val system: ActorSystem, mat: Materializer)
    extends PlayJsonSupport
    with PodConversion with StrictLogging {
  implicit val scheduler = system.scheduler
  import AkkaHttpResponse._
  import mesosphere.marathon.core.async.ExecutionContexts.global

  require(baseGroup.absolute)

  import mesosphere.marathon.api.v2.json.Formats._
  import play.api.libs.json._

  implicit lazy val itAppDefinitionFormat = Json.format[ITAppDefinition]
  implicit lazy val appUpdateMarshaller = playJsonMarshaller[AppUpdate]
  implicit lazy val itListAppsResultFormat = Json.format[ITListAppsResult]
  implicit lazy val itAppVersionsFormat = Json.format[ITAppVersions]
  implicit lazy val itListTasksFormat = Json.format[ITListTasks]
  implicit lazy val itDeploymentPlanFormat = Json.format[ITDeploymentPlan]
  implicit lazy val itHealthCheckResultFormat = Json.format[ITHealthCheckResult]
  implicit lazy val itDeploymentResultFormat = Json.format[ITDeploymentResult]
  implicit lazy val itLeaderResultFormat = Json.format[ITLeaderResult]
  implicit lazy val itDeploymentFormat = Json.format[ITDeployment]
  implicit lazy val itListDeploymentsFormat = Json.format[ITListDeployments]
  implicit lazy val itQueueDelayFormat = Json.format[ITQueueDelay]
  implicit lazy val itQueueItemFormat = Json.format[ITQueueItem]
  implicit lazy val itLaunchQueueFormat = Json.format[ITLaunchQueue]

  implicit lazy val itEnrichedTaskFormat: Format[ITEnrichedTask] = (
    (__ \ "appId").format[String] ~
    (__ \ "id").format[String] ~
    (__ \ "host").format[String] ~
    (__ \ "ports").formatNullable[Seq[Int]] ~
    (__ \ "slaveId").formatNullable[String] ~
    (__ \ "startedAt").formatNullable[Date] ~
    (__ \ "stagedAt").formatNullable[Date] ~
    (__ \ "state").format[String] ~
    (__ \ "version").formatNullable[String]
  )(ITEnrichedTask(_, _, _, _, _, _, _, _, _), unlift(ITEnrichedTask.unapply))

  def isInBaseGroup(pathId: PathId): Boolean = {
    pathId.path.startsWith(baseGroup.path)
  }

  def requireInBaseGroup(pathId: PathId): Unit = {
    require(isInBaseGroup(pathId), s"pathId $pathId must be in baseGroup ($baseGroup)")
  }

  // we don't want to lose any events and the default maxEventSize is too small (8K)
  object EventUnmarshalling extends EventStreamUnmarshalling {
    override protected def maxEventSize: Int = Int.MaxValue
    override protected def maxLineSize: Int = Int.MaxValue
  }

  /**
    * Connects to the Marathon SSE endpoint. Future completes when the http connection is established. Events are
    * streamed via the materializable-once Source.
    */
  def events(eventsType: String*): Future[Source[ITEvent, NotUsed]] = {

    import EventUnmarshalling.fromEventStream
    val mapper = new ObjectMapper() with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)

    val eventsFilter = Query(eventsType.map(eventType => "event_type" -> eventType): _*)

    Http().singleRequest(Get(akka.http.scaladsl.model.Uri(s"$url/v2/events").withQuery(eventsFilter))
      .withHeaders(Accept(MediaType.text("event-stream"))))
      .flatMap { response =>
        AkkaUnmarshal(response).to[Source[ServerSentEvent, NotUsed]]
      }
      .map { stream =>
        stream
          .map { event =>
            event.data.map { d =>
              val json = mapper.readValue[Map[String, Any]](d) // linter:ignore
              ITEvent(event.`type`.getOrElse("unknown"), json)
            }
          }
          .collect { case Some(event) => event }
      }
  }

  //app resource ----------------------------------------------

  def listAppsInBaseGroup: RestResult[List[App]] = {
    val res = result(requestFor[ITListAppsResult](Get(s"$url/v2/apps")), waitTime)
    res.map(_.apps.filterAs(app => isInBaseGroup(PathId(app.id)))(collection.breakOut))
  }

  def app(id: PathId): RestResult[ITAppDefinition] = {
    requireInBaseGroup(id)
    val getUrl: String = s"$url/v2/apps$id"
    LoggerFactory.getLogger(getClass).info(s"get url = $getUrl")
    result(requestFor[ITAppDefinition](Get(getUrl)), waitTime)
  }

  def createAppV2(app: App): RestResult[App] = {
    requireInBaseGroup(PathId(app.id))
    result(requestFor[App](Post(s"$url/v2/apps", app)), waitTime)
  }

  def deleteApp(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    result(requestFor[ITDeploymentResult](Delete(s"$url/v2/apps$id?force=$force")), waitTime)
  }

  def updateApp(id: PathId, app: AppUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val putUrl: String = s"$url/v2/apps$id?force=$force"
    LoggerFactory.getLogger(getClass).info(s"put url = $putUrl")

    result(requestFor[ITDeploymentResult](Put(putUrl, app)), waitTime)
  }

  def patchApp(id: PathId, app: AppUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val putUrl: String = s"$url/v2/apps$id?force=$force"
    LoggerFactory.getLogger(getClass).info(s"put url = $putUrl")

    result(requestFor[ITDeploymentResult](Patch(putUrl, app)), waitTime)
  }

  def restartApp(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    result(requestFor[ITDeploymentResult](Post(s"$url/v2/apps$id/restart?force=$force")), waitTime)
  }

  def listAppVersions(id: PathId): RestResult[ITAppVersions] = {
    requireInBaseGroup(id)
    result(requestFor[ITAppVersions](Get(s"$url/v2/apps$id/versions")), waitTime)
  }

  def appVersion(id: PathId, version: Timestamp): RestResult[App] = {
    requireInBaseGroup(id)
    result(requestFor[App](Get(s"$url/v2/apps$id/versions/$version")), waitTime)
  }

  //pod resource ---------------------------------------------

  def listPodsInBaseGroup: RestResult[Seq[PodDefinition]] = {
    val res = result(requestFor[Seq[Pod]](Get(s"$url/v2/pods")), waitTime)
    res.map(_.map(Raml.fromRaml(_))).map(_.filter(pod => isInBaseGroup(pod.id)))
  }

  def pod(id: PathId): RestResult[PodDefinition] = {
    requireInBaseGroup(id)
    val res = result(requestFor[Pod](Get(s"$url/v2/pods$id")), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def createPodV2(pod: PodDefinition): RestResult[PodDefinition] = {
    requireInBaseGroup(pod.id)
    val res = result(requestFor[Pod](Post(s"$url/v2/pods", Raml.toRaml(pod))), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def deletePod(id: PathId, force: Boolean = false): RestResult[HttpResponse] = {
    requireInBaseGroup(id)
    result(request(Delete(s"$url/v2/pods$id?force=$force")), waitTime)
  }

  def updatePod(id: PathId, pod: PodDefinition, force: Boolean = false): RestResult[PodDefinition] = {
    requireInBaseGroup(id)
    val res = result(requestFor[Pod](Put(s"$url/v2/pods$id?force=$force", pod)), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def status(podId: PathId): RestResult[PodStatus] = {
    requireInBaseGroup(podId)
    result(requestFor[PodStatus](Get(s"$url/v2/pods$podId::status")), waitTime)
  }

  def listPodVersions(podId: PathId): RestResult[Seq[Timestamp]] = {
    requireInBaseGroup(podId)
    result(requestFor[Seq[Timestamp]](Get(s"$url/v2/pods$podId::versions")), waitTime)
  }

  def podVersion(podId: PathId, version: Timestamp): RestResult[PodDefinition] = {
    requireInBaseGroup(podId)
    val res = result(requestFor[Pod](Get(s"$url/v2/pods$podId::versions/$version")), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def deleteAllInstances(podId: PathId): RestResult[List[PodInstanceStatus]] = {
    requireInBaseGroup(podId)
    result(requestFor[List[PodInstanceStatus]](Delete(s"$url/v2/pods$podId::instances")), waitTime)
  }

  def deleteInstance(podId: PathId, instance: String): RestResult[PodInstanceStatus] = {
    requireInBaseGroup(podId)
    result(requestFor[PodInstanceStatus](Delete(s"$url/v2/pods$podId::instances/$instance")), waitTime)
  }

  //apps tasks resource --------------------------------------

  private val log = LoggerFactory.getLogger(getClass)

  def tasks(appId: PathId): RestResult[List[ITEnrichedTask]] = {
    requireInBaseGroup(appId)
    val res = result(requestFor[ITListTasks](Get(s"$url/v2/apps$appId/tasks")), waitTime)
    res.map(_.tasks.toList)
  }

  def killAllTasks(appId: PathId, scale: Boolean = false): RestResult[ITListTasks] = {
    requireInBaseGroup(appId)
    result(requestFor[ITListTasks](Delete(s"$url/v2/apps$appId/tasks?scale=$scale")), waitTime)
  }

  def killAllTasksAndScale(appId: PathId): RestResult[ITDeploymentPlan] = {
    requireInBaseGroup(appId)
    result(requestFor[ITDeploymentPlan](Delete(s"$url/v2/apps$appId/tasks?scale=true")), waitTime)
  }

  def killTask(appId: PathId, taskId: String, scale: Boolean = false): RestResult[HttpResponse] = {
    requireInBaseGroup(appId)
    result(request(Delete(s"$url/v2/apps$appId/tasks/$taskId?scale=$scale")), waitTime)
  }

  //group resource -------------------------------------------

  def listGroupsInBaseGroup: RestResult[Set[GroupInfo]] = {
    import PathId._
    val root = result(requestFor[GroupInfo](Get(s"$url/v2/groups")), waitTime)
    root.map(_.groups.filter(group => isInBaseGroup(group.id.toPath)))
  }

  def listGroupVersions(id: PathId): RestResult[List[String]] = {
    requireInBaseGroup(id)
    result(requestFor[List[String]](Get(s"$url/v2/groups$id/versions")), waitTime)
  }

  def group(id: PathId): RestResult[GroupInfo] = {
    requireInBaseGroup(id)
    result(requestFor[GroupInfo](Get(s"$url/v2/groups$id")), waitTime)
  }

  def createGroup(group: GroupUpdate): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(group.id.map(PathId(_)).getOrElse(throw new IllegalArgumentException("missing group.id")))
    result(requestFor[ITDeploymentResult](Post(s"$url/v2/groups", group)), waitTime)
  }

  def deleteGroup(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    result(requestFor[ITDeploymentResult](Delete(s"$url/v2/groups$id?force=$force")), waitTime)
  }

  def deleteRoot(force: Boolean): RestResult[ITDeploymentResult] = {
    result(requestFor[ITDeploymentResult](Delete(s"$url/v2/groups?force=$force")), waitTime)
  }

  def updateGroup(id: PathId, group: GroupUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    result(requestFor[ITDeploymentResult](Put(s"$url/v2/groups$id?force=$force", group)), waitTime)
  }

  def rollbackGroup(groupId: PathId, version: Timestamp, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(groupId)
    updateGroup(groupId, GroupUpdate(None, version = Some(version.toOffsetDateTime)), force)
  }

  //deployment resource ------

  def listDeploymentsForBaseGroup(): RestResult[List[ITDeployment]] = {
    result(requestFor[List[ITDeployment]](Get(s"$url/v2/deployments")), waitTime).map { deployments =>
      deployments.filter { deployment =>
        deployment.affectedApps.map(PathId(_)).exists(id => isInBaseGroup(id)) ||
          deployment.affectedPods.map(PathId(_)).exists(id => isInBaseGroup(id))
      }
    }
  }

  def deleteDeployment(id: String, force: Boolean = false): RestResult[HttpResponse] = {
    result(request(Delete(s"$url/v2/deployments/$id?force=$force")), waitTime)
  }

  //metrics ---------------------------------------------

  def metrics(): RestResult[HttpResponse] = {
    result(request(Get(s"$url/metrics")), waitTime)
  }

  def ping(): RestResult[HttpResponse] = {
    result(request(Get(s"$url/ping")), waitTime)
  }

  //leader ----------------------------------------------
  def leader(): RestResult[ITLeaderResult] = {
    result(leaderAsync(), waitTime)
  }

  def leaderAsync(): Future[RestResult[ITLeaderResult]] = {
    Retry("leader") { requestFor[ITLeaderResult](Get(s"$url/v2/leader")) }
  }

  def abdicate(): RestResult[HttpResponse] = {
    result(Retry("abdicate") { request(Delete(s"$url/v2/leader")) }, waitTime)
  }

  def abdicateWithBackup(file: String): RestResult[HttpResponse] = {
    result(Retry("abdicate") { request(Delete(s"$url/v2/leader?backup=file://$file")) }, waitTime)
  }

  //info --------------------------------------------------
  def info: RestResult[HttpResponse] = {
    result(request(Get(s"$url/v2/info")), waitTime)
  }

  //launch queue ------------------------------------------
  def launchQueue(): RestResult[ITLaunchQueue] = {
    result(requestFor[ITLaunchQueue](Get(s"$url/v2/queue")), waitTime)
  }

  //resources -------------------------------------------

  def getPath(path: String): RestResult[HttpResponse] = {
    result(request(Get(s"$url$path")), waitTime)
  }
}

object MarathonFacade {
  def extractDeploymentIds(app: RestResult[App]): Seq[String] = {
    try {
      for (deployment <- (app.entityJson \ "deployments").as[JsArray].value)
        yield (deployment \ "id").as[String]
    } catch {
      case NonFatal(e) =>
        throw new RuntimeException(s"while parsing:\n${app.entityPrettyJsonString}", e)
    }
  }.toIndexedSeq
}
