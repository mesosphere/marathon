package mesosphere.marathon.event.http

import akka.actor._
import akka.pattern.ask
import spray.client.pipelining.sendReceive
import scala.concurrent.Future
import spray.httpx.Json4sJacksonSupport
import org.json4s.{DefaultFormats, FieldSerializer}
import spray.client.pipelining._
import mesosphere.marathon.event.MarathonEvent
import mesosphere.marathon.api.v1.AppDefinition
import spray.http.HttpRequest
import spray.http.HttpResponse
import scala.util.Success
import scala.util.Failure
import mesosphere.marathon.event.http.SubscribersKeeperActor.GetSubscribers

class HttpEventActor(val subscribersKeeper: ActorRef) extends Actor with ActorLogging with Json4sJacksonSupport {

  implicit val ec = HttpEventModule.executionContext
  implicit val timeout = HttpEventModule.timeout

  val pipeline: HttpRequest => Future[HttpResponse] = (
    addHeader("Accept", "application/json")
      ~> sendReceive)

  def receive = {
    case event: MarathonEvent =>
      broadcast(event)
    case _ =>
      log.warning("Message not understood!")
  }

  def broadcast(event: MarathonEvent): Unit = {
    log.info("POSTing to all endpoints.")
    (subscribersKeeper ? GetSubscribers).mapTo[EventSubscribers].foreach {
      _.urls.foreach { post(_,event) }
    }
  }

  def post(urlString: String, event: MarathonEvent) {
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

  implicit def json4sJacksonFormats = DefaultFormats + FieldSerializer
    [AppDefinition]()
}



