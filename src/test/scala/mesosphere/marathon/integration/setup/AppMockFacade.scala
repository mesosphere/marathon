package mesosphere.marathon
package integration.setup

import akka.actor.{ ActorSystem, Scheduler }
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.util.Retry

import scala.async.Async._
import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, _ }

case class AppMockResponse(asString: String, response: HttpResponse)

class AppMockFacade(https: Boolean = false, waitTime: Duration = 30.seconds)(implicit system: ActorSystem, mat: Materializer) extends StrictLogging {
  import mesosphere.marathon.core.async.ExecutionContexts.global

  implicit val scheduler: Scheduler = system.scheduler

  def ping(host: String, port: Int): Future[AppMockResponse] = custom("/ping")(host, port)

  val scheme: String = if (https) "https" else "http"

  def custom(uri: String)(host: String, port: Int): Future[AppMockResponse] = {
    val url = s"$scheme://$host:$port$uri"
    Retry(s"query$url", Int.MaxValue, maxDuration = waitTime) {
      async {
        val response = await(Http().singleRequest(RequestBuilding.Get(url)))
        val body = await(Unmarshal(response.entity).to[String])
        AppMockResponse(body, response)
      }
    }
  }
}
