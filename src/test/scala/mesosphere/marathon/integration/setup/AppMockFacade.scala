package mesosphere.marathon.integration.setup

import akka.actor.ActorSystem
import org.slf4j.LoggerFactory
import spray.client.pipelining._

import scala.concurrent.duration.{ Duration, _ }
import scala.util.control.NonFatal

class AppMockFacade(https: Boolean = false, waitTime: Duration = 30.seconds)(implicit system: ActorSystem) {
  import mesosphere.util.ThreadPoolContext.context

  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] def retry[T](retries: Int = 50, waitForNextTry: Duration = 50.milliseconds)(block: => T): T = {
    try {
      block
    }
    catch {
      case NonFatal(e) =>
        log.info("will retry after {}", waitForNextTry: Any, e: Any)
        Thread.sleep(waitForNextTry.toMillis)
        retry(retries = retries - 1, waitForNextTry = waitForNextTry)(block)
    }
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
