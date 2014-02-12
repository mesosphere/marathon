package mesosphere.marathon.event.http

import akka.actor._

import spray.client.pipelining.sendReceive
import scala.concurrent.Future
import spray.httpx.Json4sJacksonSupport
import org.json4s.{DefaultFormats, FieldSerializer}
import spray.client.pipelining._
import mesosphere.marathon.event.{Unsubscribe, Subscribe, MarathonEvent}
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.Main
import spray.http.HttpRequest
import spray.http.HttpResponse
import scala.util.Success
import scala.util.Failure

class HttpEventActor extends Actor with ActorLogging with Json4sJacksonSupport {

  implicit val ec = HttpEventModule.executionContext

  // you don't have to take care of thread-safety
  // because actors processes their own messages sequentially.
  var urls = Main.conf.httpEventEndpoints.get.map(
    _.foldLeft(Set.empty[String]) {
      (set, url) => set + url
    }
  ).getOrElse(Set.empty[String])

  val pipeline: HttpRequest => Future[HttpResponse] = (
    addHeader("Accept", "application/json")
      ~> sendReceive)

  def receive = subscription orElse {
    case event: MarathonEvent =>
      broadcast(event)
    case _ => {
      log.warning("Message not understood!")
    }
  }

  val subscription: Receive = {
    case event@Subscribe(_, callback_url, _) =>
      try {
        if (!urls.contains(callback_url)) {
          urls += callback_url
          log.info(s"call back url ${callback_url} is registered.")
        } else {
          log.info(s"call back url ${callback_url} has been already registered. so it will not added.")
        }
        broadcast(event) // subscribe event should be broadcasted.
        sender ! event
      } catch {
        case t: Throwable => sender ! s"caught exception: ${t.getMessage}"
      }


    case event@Unsubscribe(_, callback_url, _) =>
      try {
        broadcast(event) // unsubscribe event should be broadcasted.
        if (urls.contains(callback_url)) {
          urls -= callback_url
          log.info(s"call back url ${callback_url} is unregistered.")
        } else {
          log.info(s"call back url ${callback_url} is unregistered.")
        }
        sender ! event
      } catch {
        case t: Throwable => sender ! s"caught exception: ${t.getMessage}"
      }

    case GetSubscribers =>
      sender ! urls.toList
  }

  def broadcast(event: MarathonEvent) = {
    log.info("POSTing to all endpoints.")
    try {
      urls.foreach(x => post(x, event))
    } catch {
      case t: Throwable => {
        log.warning("Caught exception:" + t.getMessage)
        throw t
      }
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

object HttpEventActor{
  case object GetSubscribers
}



