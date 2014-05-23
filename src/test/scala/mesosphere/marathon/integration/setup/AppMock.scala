package mesosphere.marathon.integration.setup

import org.eclipse.jetty.server.{Request, Server}
import org.eclipse.jetty.server.handler.AbstractHandler
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import akka.actor.ActorSystem
import spray.client.pipelining._
import scala.concurrent.Await._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class AppMock(url:String) extends AbstractHandler {

  implicit val system = ActorSystem()
  val pipeline = sendReceive
  val waitTime = 30.seconds

  def start(port:Int) {
    val server = new Server(port)
    server.setHandler(this)
    server.start()
    server.join()
  }

  override def handle(target: String,
                      baseRequest: Request,
                      request: HttpServletRequest,
                      response: HttpServletResponse): Unit = {
    val res = result(pipeline(Get(url)), waitTime)
    response.setStatus(res.status.intValue)
    baseRequest.setHandled(true)
    response.getWriter.print(res.entity.asString)
  }
}

object AppMock {
  def main(args: Array[String]) {
    val port = sys.env("PORT0").toInt
    val url = args(0) + "/" + port
    new AppMock(url).start(port)
  }
}

