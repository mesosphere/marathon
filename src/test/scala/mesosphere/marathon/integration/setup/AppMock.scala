package mesosphere.marathon.integration.setup

import java.lang.management.ManagementFactory

import org.eclipse.jetty.server.{ Request, Server }
import org.eclipse.jetty.server.handler.AbstractHandler
import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }
import akka.actor.ActorSystem
import spray.client.pipelining._
import scala.concurrent.Await._
import scala.concurrent.duration._

class AppMock(appId: String, version: String, url: String) extends AbstractHandler {
  import mesosphere.util.ThreadPoolContext.context

  implicit val system = ActorSystem()
  val pipeline = sendReceive
  val waitTime = 30.seconds

  val processId = ManagementFactory.getRuntimeMXBean.getName

  def start(port: Int) {
    val server = new Server(port)
    server.setHandler(this)
    server.start()
    println(s"AppMock[$appId $version]: has taken the stage at port $port. Will query $url for health status.")
    server.join()
    println(s"AppMock[$appId $version]: says goodbye")
  }

  override def handle(target: String,
                      baseRequest: Request,
                      request: HttpServletRequest,
                      response: HttpServletResponse): Unit = {
    val res = result(pipeline(Get(url)), waitTime)
    println(s"AppMock[$appId $version]: current health is $res")
    response.setStatus(res.status.intValue)
    baseRequest.setHandled(true)
    response.getWriter.print(res.entity.asString)
  }
}

object AppMock {
  def main(args: Array[String]) {
    val port = sys.env("PORT0").toInt
    val appId = args(0)
    val version = args(1)
    val url = args(2) + "/" + port
    new AppMock(appId, version, url).start(port)
  }
}

