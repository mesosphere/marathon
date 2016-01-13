package mesosphere.marathon.integration.setup

import akka.actor.ActorSystem
import org.slf4j.LoggerFactory
import spray.client.pipelining._

import scala.concurrent.duration.{ Duration, _ }
import scala.util.Try

class AppMockFacade(https: Boolean = false, waitTime: Duration = 30.seconds)(implicit system: ActorSystem) {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] def retry[T](retries: Int = 200, waitForNextTry: Duration = 50.milliseconds)(block: => T): T = {
    val attempts = Iterator(Try(block)) ++ Iterator.continually(Try {
      Thread.sleep(waitForNextTry.toMillis)
      block
    })
    val firstSuccess = attempts.take(retries - 1).find(_.isSuccess).flatMap(_.toOption)
    firstSuccess.getOrElse(block)
  }

  val pipeline = sendReceive
  def ping(host: String, port: Int): RestResult[String] = custom("/ping")(host, port)

  def scheme: String = if (https) "https" else "http"

  def custom(uri: String)(host: String, port: Int): RestResult[String] = {
    retry() {
      RestResult.await(pipeline(Get(s"$scheme://$host:$port$uri")), waitTime).map(_.entity.asString)
    }
  }
}
