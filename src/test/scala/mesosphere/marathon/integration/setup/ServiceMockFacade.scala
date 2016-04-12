package mesosphere.marathon.integration.setup

import akka.actor.ActorSystem
import mesosphere.marathon.integration.facades.ITEnrichedTask
import org.slf4j.LoggerFactory
import play.api.libs.json.{ JsValue, Json }
import spray.client.pipelining._
import spray.http.HttpResponse

import scala.concurrent.duration.{ Duration, _ }

class ServiceMockFacade(task: ITEnrichedTask, waitTime: Duration = 30.seconds)(implicit system: ActorSystem) {
  import scala.concurrent.ExecutionContext.Implicits.global

  val log = LoggerFactory.getLogger(classOf[ServiceMockFacade])

  val baseUrl = s"http://${task.host}:${task.ports.map(_.head).get}"

  val pipeline = sendReceive

  def continue(): RestResult[HttpResponse] = {
    log.info(s"Continue with the service migration: $baseUrl/v1/plan/continue")
    RestResult.await(pipeline(Post(s"$baseUrl/v1/plan/continue")), waitTime)
  }

  def plan(): RestResult[JsValue] = {
    RestResult.await(pipeline(Get(s"$baseUrl/v1/plan")), waitTime).map(_.entity.asString).map(Json.parse)
  }
}
