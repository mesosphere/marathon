package mesosphere.marathon.integration.setup

import mesosphere.marathon.state.PathId._
import play.api.libs.json.{ Json, JsValue }

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Awaitable }
import scala.reflect.ClassTag
import com.google.inject.Scopes
import javax.ws.rs._
import javax.ws.rs.core.{ Response, MediaType }
import javax.inject.Inject
import org.apache.log4j.Logger
import spray.httpx.marshalling.{ MarshallingContext, Marshaller }
import spray.http.{ ContentTypes, HttpEntity }
import spray.httpx.UnsuccessfulResponseException
import spray.http.HttpResponse
import mesosphere.chaos.http.RestModule
import mesosphere.marathon.api.MarathonRestModule

/**
  * Result of an REST operation.
  */
case class RestResult[+T](value: T, originalResponse: HttpResponse) {
  def code: Int = originalResponse.status.intValue
  def success: Boolean = code == 200

  /** Transform the value of this result. */
  def map[R](change: T => R): RestResult[R] = {
    val changed: R = change(value)
    RestResult(changed, originalResponse)
  }

  /** Display the original response entity (=body) as string. */
  def entityString: String = originalResponse.entity.asString

  /** Parse the original response entity (=body) as json. */
  def entityJson: JsValue = Json.parse(entityString)

  /** Pretty print the original response entity (=body) as json. */
  def entityPrettyJsonString: String = Json.prettyPrint(entityJson)
}

object RestResult {
  def apply(response: HttpResponse): RestResult[HttpResponse] = {
    new RestResult[HttpResponse](response, response)
  }

  def await(responseFuture: Awaitable[HttpResponse], waitTime: Duration): RestResult[HttpResponse] = {
    apply(Await.result(responseFuture, waitTime))
  }
}

/**
  * Marshal and Unmarshal json via jackson jaxb over spray http client.
  */
trait JacksonSprayMarshaller {

  val mapper = new MarathonRestModule().provideRestMapper()

  def marshaller[T]: Marshaller[T] = new Marshaller[T] {
    def apply(value: T, ctx: MarshallingContext): Unit = {
      ctx.marshalTo(HttpEntity(ContentTypes.`application/json`, mapper.writeValueAsString(value)))
    }
  }

  def read[T](implicit tag: ClassTag[T]): HttpResponse ⇒ RestResult[T] =
    response ⇒
      if (response.status.isSuccess) {
        RestResult(response).map { response =>
          mapper.readValue(response.entity.asString, tag.runtimeClass.asInstanceOf[Class[T]])
        }
      }
      else {
        throw new UnsuccessfulResponseException(response)
      }

  def responseResult: HttpResponse => RestResult[HttpResponse] =
    response => RestResult(response)
  def toList[T]: RestResult[Array[T]] => RestResult[List[T]] =
    result => result.map(_.toList)
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
case class CallbackEvent(eventType: String, info: Map[String, Any])

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

