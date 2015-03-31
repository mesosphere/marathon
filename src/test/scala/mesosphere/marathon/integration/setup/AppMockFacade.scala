package mesosphere.marathon.integration.setup

import akka.actor.ActorSystem
import org.slf4j.LoggerFactory
import spray.client.pipelining._

import scala.concurrent.duration.{ Duration, _ }
import scala.util.control.NonFatal

class AppMockFacade(waitTime: Duration = 30.seconds)(implicit system: ActorSystem) {
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
  def ping(host: String, port: Int): RestResult[String] = {
    retry() {
      RestResult.await(pipeline(Get(s"http://$host:$port/ping")), waitTime).map(_.entity.asString)
    }
  }
}
