package mesosphere.marathon.event.http

import akka.actor._

import spray.client.pipelining.sendReceive
import scala.concurrent.Future
import spray.httpx.Json4sJacksonSupport
import org.json4s.{DefaultFormats, FieldSerializer}
import spray.client.pipelining._
import mesosphere.marathon.event.MarathonEvent
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.Main
import spray.http.HttpRequest
import spray.http.HttpResponse
import scala.util.Success
import scala.util.Failure

class HttpEventActor extends Actor with ActorLogging with Json4sJacksonSupport {

  implicit val ec = HttpEventModule.executionContext

  val urls = Main.getConfiguration.httpEventEndpoints()

  val pipeline: HttpRequest => Future[HttpResponse] = (
    addHeader("Accept", "application/json")
      ~> sendReceive)

  def receive = {
    case event: MarathonEvent => {
      log.info("POSTing to all endpoints.")
      try {
        urls.foreach(x => post(x, event))
      } catch {
        case t: Throwable => {
          log.warning("Caught exception:" + t.getMessage)
        }
      }
    }
    case _ => {
      log.warning("Message not understood!")
    }
  }

  def post(urlString: String, event: MarathonEvent) {
    log.info("Sending POST to:" + urlString)
    val request = Post(urlString, event)
    val response = pipeline(request)

    response.onComplete {
      case Success(res) => {
        if (res.status.isFailure) {
          log.warning(s"Failed to post $event")
        }
      }
      case Failure(t) => {
        log.warning(s"Failed to post $event")
      }
    }
  }

  implicit def json4sJacksonFormats = DefaultFormats + FieldSerializer
    [AppDefinition]()
}





