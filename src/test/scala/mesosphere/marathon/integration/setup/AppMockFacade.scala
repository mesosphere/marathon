package mesosphere.marathon
package integration.setup

import akka.actor.ActorSystem
import mesosphere.marathon.util.Retry
import org.slf4j.LoggerFactory
import spray.client.pipelining._

import scala.concurrent.Await.result
import scala.concurrent.duration.{ Duration, _ }

class AppMockFacade(https: Boolean = false, waitTime: Duration = 30.seconds)(implicit system: ActorSystem) {
  import scala.concurrent.ExecutionContext.Implicits.global

  import SprayHttpResponse._
  implicit val scheduler = system.scheduler

  private[this] val log = LoggerFactory.getLogger(getClass)

  def ping(host: String, port: Int): RestResult[String] = custom("/ping")(host, port)

  def scheme: String = if (https) "https" else "http"

  def custom(uri: String)(host: String, port: Int): RestResult[String] = {
    val url = s"$scheme://$host:$port$uri"
    val pipeline = sendReceive ~> read[String]
    result(Retry(s"query:$url", 10, 2.seconds, 10.seconds) { pipeline(Get(url)) }, waitTime)
  }
}
