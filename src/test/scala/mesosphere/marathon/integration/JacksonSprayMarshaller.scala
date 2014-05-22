package mesosphere.marathon.integration

import scala.reflect.ClassTag
import com.google.inject.Scopes
import javax.ws.rs._
import javax.ws.rs.core.{Response, MediaType}
import javax.inject.Inject
import org.apache.log4j.Logger
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.concurrent.Await.result
import spray.httpx.marshalling.{MarshallingContext, Marshaller}
import spray.http.{ContentTypes, HttpEntity}
import spray.httpx.UnsuccessfulResponseException
import spray.client.pipelining._
import spray.http.HttpResponse
import mesosphere.chaos.http.RestModule
import mesosphere.marathon.api.MarathonRestModule
import mesosphere.marathon.api.v2.Group
import mesosphere.marathon.event.http.EventSubscribers
import mesosphere.marathon.event.{Unsubscribe, Subscribe}
import mesosphere.marathon.api.v1.AppDefinition
import java.util.Date

/**
 * Result of an REST operation.
 */
case class RestResult[T](value:T, code:Int) {
  def success = code==200
}

/**
 * Marshal and Unmarshal json via jackson jaxb over spray http client.
 */
trait JacksonSprayMarshaller {

  val mapper = new MarathonRestModule().provideRestMapper()

  def marshaller[T] : Marshaller[T] = new Marshaller[T] {
    def apply(value: T, ctx: MarshallingContext): Unit = {
      ctx.marshalTo(HttpEntity(ContentTypes.`application/json`, mapper.writeValueAsString(value)))
    }
  }

  def read[T](implicit tag:ClassTag[T]): HttpResponse ⇒ RestResult[T] =
    response ⇒
      if (response.status.isSuccess) {
        val value = mapper.readValue(response.entity.asString, tag.runtimeClass.asInstanceOf[Class[T]])
        RestResult(value, response.status.intValue)
      } else {
        throw new UnsuccessfulResponseException(response)
      }

  def responseResult : HttpResponse => RestResult[HttpResponse] = response => RestResult(response, response.status.intValue)
  def toList[T]: RestResult[Array[T]] => RestResult[List[T]] = result => RestResult(result.value.toList, result.code)
}

/**
 * GET /apps will deliver something like Apps instead of List[App]
 * Needed for dumb jackson.
 */
case class ListAppsResult(apps:Seq[AppDefinition])
case class ListTasks(tasks:Seq[ITEnrichedTask])
case class ITEnrichedTask(appId:String, id:String, host:String, ports:Seq[Integer], startedAt:Date, stagedAt:Date, version:String)
/**
 * The MarathonFacade offers the REST API of a remote marathon instance
 * with all local domain objects.
 * @param url the url of the remote marathon instance
 */
class MarathonFacade(url:String, waitTime:Duration=30.seconds) extends JacksonSprayMarshaller {

  implicit val system = ActorSystem()
  implicit val appDefMarshaller = marshaller[AppDefinition]
  implicit val groupMarshaller = marshaller[Group]

  //app resource ----------------------------------------------

  def listApps: RestResult[List[AppDefinition]] = {
    val pipeline = sendReceive ~> read[ListAppsResult]
    val res = result(pipeline(Get(s"$url/v2/apps")), waitTime)
    RestResult(res.value.apps.toList, res.code)
  }

  def app(id:String): RestResult[AppDefinition] = {
    val pipeline = sendReceive ~> read[AppDefinition]
    result(pipeline(Get(s"$url/v2/apps/$id")), waitTime)
  }

  def createApp(app:AppDefinition): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Post(s"$url/v2/apps", app)), waitTime)
  }

  def deleteApp(id:String): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/apps/$id")), waitTime)
  }

  def updateApp(app:AppDefinition): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Put(s"$url/v2/apps/${app.id}", app)), waitTime)
  }

  //apps tasks resource --------------------------------------

  def tasks(appId:String): RestResult[List[ITEnrichedTask]] = {
    val pipeline = sendReceive ~> read[ListTasks]
    val res = result(pipeline(Get(s"$url/v2/apps/$appId/tasks")), waitTime)
    RestResult(res.value.tasks.toList, res.code)
  }

  //group resource -------------------------------------------

  def listGroups: RestResult[List[Group]] = {
    val pipeline = sendReceive ~> read[Array[Group]] ~> toList[Group]
    result(pipeline(Get(s"$url/v2/groups")), waitTime)
  }

  def group(id:String): RestResult[Group] = {
    val pipeline = sendReceive ~> read[Group]
    result(pipeline(Get(s"$url/v2/groups/$id")), waitTime)
  }

  def createGroup(group:Group): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Post(s"$url/v2/groups", group)), waitTime)
  }

  def deleteGroup(id:String): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/groups/$id")), waitTime)
  }

  def updateGroup(group:Group): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Put(s"$url/v2/groups/${group.id}", group)), waitTime)
  }

  //event resource ---------------------------------------------

  def listSubscribers: RestResult[EventSubscribers] = {
    val pipeline = sendReceive ~> read[EventSubscribers]
    result(pipeline(Get(s"$url/v2/eventSubscriptions")), waitTime)
  }

  def subscribe(callbackUrl:String): RestResult[Subscribe] = {
    val pipeline = sendReceive ~> read[Subscribe]
    result(pipeline(Post(s"$url/v2/eventSubscriptions?callbackUrl=$callbackUrl")), waitTime)
  }

  def unsubscribe(callbackUrl:String): RestResult[Unsubscribe] = {
    val pipeline = sendReceive ~> read[Unsubscribe]
    result(pipeline(Delete(s"$url/v2/eventSubscriptions?callbackUrl=$callbackUrl")), waitTime)
  }

  //convenience functions ---------------------------------------

  /**
   * Delete all existing apps, groups and event subscriptions.
   * @param maxWait the maximal wait time for the cleaning process.
   */
  def cleanUp(withSubscribers:Boolean=false, maxWait:FiniteDuration=30.seconds) = {
    val deadline = maxWait.fromNow
    listGroups.value.map(_.id).foreach(deleteGroup)
    listApps.value.map(_.id).foreach(deleteApp)
    if (withSubscribers) listSubscribers.value.urls.foreach(unsubscribe)
    def waitForReady():Unit = {
      if (deadline.isOverdue()) {
        throw new IllegalStateException(s"Can not clean marathon to default state after $maxWait. Give up.")
      } else {
        val ready = listGroups.value.isEmpty && listApps.value.isEmpty && (listSubscribers.value.urls.isEmpty || !withSubscribers)
        if (!ready && !deadline.isOverdue()) {
          Thread.sleep(100)
          waitForReady()
        }
      }
    }
    waitForReady()
  }
}

/**
 * Guava integration test module, which start a local http server.
 */
class IntegrationTestModule extends RestModule {
  override def configureServlets(): Unit = {
    super.configureServlets()
    bind(classOf[CallbackEventHandler]).in(Scopes.SINGLETON)
    bind(classOf[ApplicationHealthCheck]).in(Scopes.SINGLETON)
  }
}

/**
 * The common data structure for all callback events.
 * Needed for dumb jackson.
 */
case class CallbackEvent(eventType:String, info:Map[String, Any])


/**
 * Callback
 */
@Path("callback")
class CallbackEventHandler @Inject() () {

  private[this] val log = Logger.getLogger(getClass.getName)

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index = List(1, 2, 3, 4, 5)

  @POST
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Produces(Array(MediaType.APPLICATION_JSON))
  def handleEvent(map:Map[String, Any]): Unit = {
    val kind = map.getOrElse("eventType", "unknown").asInstanceOf[String]
    log.info(s"Received callback event: $kind with props $map")
    val event = CallbackEvent(kind, map)
    ExternalMarathonIntegrationTest.listener.foreach(_.handleEvent(event))
  }
}

@Path("health")
class ApplicationHealthCheck @Inject() () {

  @GET
  @Path("{id}")
  def isApplicationHealthy(@PathParam("id") id:String) : Response = {
    val state = ExternalMarathonIntegrationTest.healthChecks.find(_.id == id).fold(false)(_.healthy)
    if (state) Response.ok().build() else Response.serverError().build()
  }
}

