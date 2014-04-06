package mesosphere.marathon.event.exec

import akka.actor._
import akka.pattern.ask
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
import mesosphere.marathon.event.exec.SubscribersKeeperActor.GetSubscribers
import scala.sys.process._
import org.json4s.jackson.{Serialization, Json4sScalaModule}
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import org.json4s.jackson.Serialization.{read,write}

class ExecEventActor(val subscribersKeeper: ActorRef) extends Actor with ActorLogging with Json4sJacksonSupport {

  implicit val ec = ExecEventModule.executionContext
  implicit val timeout = ExecEventModule.timeout

  val pipeline: HttpRequest => Future[HttpResponse] = (
    addHeader("Accept", "application/json")
      ~> sendReceive)

  def receive = {
    case event: MarathonEvent =>
      broadcast(event)
    case _ => {
      log.warning("Message not understood!")
    }
  }

  def broadcast(event: MarathonEvent): Unit = {
    log.info("Executing command endpoints")
    (subscribersKeeper ? GetSubscribers).mapTo[EventSubscribers].foreach {
      _.urls.foreach { exec(_,event) }
    }
  }

  def exec(cmdString: String, event: MarathonEvent) {
    log.info("Executing command: " + cmdString)
    val stdin = write(event)
    log.info("Got json: " + stdin)
    val is = new ByteArrayInputStream(stdin.getBytes("UTF-8"))
    val res = (Process(cmdString) #< is).!!
    log.info("Result: " + res)
  }

  implicit def json4sJacksonFormats = DefaultFormats + FieldSerializer
    [AppDefinition]()
}



