package mesosphere.marathon.integration.setup

import javax.inject.Inject
import javax.ws.rs._
import javax.ws.rs.core.{ MediaType, Response }

import com.google.inject.Scopes
import mesosphere.chaos.http.RestModule
import mesosphere.marathon.state.PathId._
import org.slf4j.LoggerFactory
import play.api.libs.json.{ JsValue, Json }
import spray.http.HttpResponse

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Awaitable }

/**
  * Result of an REST operation.
  */
case class RestResult[+T](valueGetter: () => T, originalResponse: HttpResponse) {
  def code: Int = originalResponse.status.intValue
  def success: Boolean = code == 200
  lazy val value: T = valueGetter()

  /** Transform the value of this result. */
  def map[R](change: T => R): RestResult[R] = {
    RestResult(() => change(valueGetter()), originalResponse)
  }

  /** Display the original response entity (=body) as string. */
  lazy val entityString: String = originalResponse.entity.asString

  /** Parse the original response entity (=body) as json. */
  lazy val entityJson: JsValue = Json.parse(entityString)

  /** Pretty print the original response entity (=body) as json. */
  lazy val entityPrettyJsonString: String = Json.prettyPrint(entityJson)
}

object RestResult {
  def apply(response: HttpResponse): RestResult[HttpResponse] = {
    new RestResult[HttpResponse](() => response, response)
  }

  def await(responseFuture: Awaitable[HttpResponse], waitTime: Duration): RestResult[HttpResponse] = {
    apply(Await.result(responseFuture, waitTime))
  }
}

/**
  * Guava integration test module, which start a local http server.
  */
class HttpServiceTestModule extends RestModule {
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
case class CallbackEvent(eventType: String, info: Map[String, Any])

/**
  * Callback
  */
@Path("callback")
class CallbackEventHandler @Inject() () {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def index = List(1, 2, 3, 4, 5)

  @POST
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Produces(Array(MediaType.APPLICATION_JSON))
  def handleEvent(map: Map[String, Any]): Unit = {
    val kind = map.get("eventType").map(_.toString).getOrElse("unknown")
    log.info(s"Received callback event: $kind with props $map")
    val event = CallbackEvent(kind, map)
    ExternalMarathonIntegrationTest.listener.foreach(_.handleEvent(event))
  }
}

@Path("health")
class ApplicationHealthCheck @Inject() () {

  @GET
  @Path("{appId:.+}/{versionId}/{port}")
  def isApplicationHealthy(@PathParam("appId") path: String, @PathParam("versionId") versionId: String, @PathParam("port") port: Int): Response = {
    val appId = path.toRootPath
    def instance = ExternalMarathonIntegrationTest.healthChecks.find{ c => c.appId == appId && c.versionId == versionId && c.port == port }
    def definition = ExternalMarathonIntegrationTest.healthChecks.find{ c => c.appId == appId && c.versionId == versionId && c.port == 0 }
    val state = instance.orElse(definition).fold(true)(_.healthy)
    if (state) Response.ok().build() else Response.serverError().build()
  }
}

