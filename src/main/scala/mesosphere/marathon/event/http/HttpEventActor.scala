package mesosphere.marathon.event.http

import akka.actor._
import akka.pattern.ask
import mesosphere.marathon.event.MarathonEvent
import mesosphere.marathon.event.http.SubscribersKeeperActor.GetSubscribers
import mesosphere.marathon.state.{ AppDefinition, PathId }
import org.json4s.JsonAST.JString
import org.json4s.{ CustomSerializer, DefaultFormats, FieldSerializer }
import spray.client.pipelining.{ sendReceive, _ }
import spray.http.{ HttpRequest, HttpResponse }
import spray.httpx.Json4sJacksonSupport

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class HttpEventActor(val subscribersKeeper: ActorRef)
    extends Actor with ActorLogging with Json4sJacksonSupport {

  implicit val ec = HttpEventModule.executionContext
  implicit val timeout = HttpEventModule.timeout

  val pipeline: HttpRequest => Future[HttpResponse] = (
    addHeader("Accept", "application/json")
    ~> sendReceive)

  def receive: Receive = {
    case event: MarathonEvent =>
      broadcast(event)
    case _ =>
      log.warning("Message not understood!")
  }

  def broadcast(event: MarathonEvent): Unit = {
    log.info("POSTing to all endpoints.")
    (subscribersKeeper ? GetSubscribers).mapTo[EventSubscribers].foreach {
      _.urls.foreach { post(_, event) }
    }
  }

  def post(urlString: String, event: MarathonEvent): Unit = {
    log.info("Sending POST to:" + urlString)

    val request = Post(urlString, event)
    val response = pipeline(request)

    response.onComplete {
      case Success(res) =>
        if (res.status.isFailure)
          log.warning(s"Failed to post $event to $urlString")

      case Failure(t) =>
        log.warning(s"Failed to post $event to $urlString")
        throw t
    }
  }

  class PathIdSerializer extends CustomSerializer[PathId](format => (
    { case JString(path) => PathId(path) },
    { case path: PathId => JString(path.toString) }
  ))

  implicit def json4sJacksonFormats: org.json4s.Formats =
    DefaultFormats + FieldSerializer[AppDefinition]() + new PathIdSerializer

}

