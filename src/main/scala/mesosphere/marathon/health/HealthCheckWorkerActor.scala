package mesosphere.marathon.health

import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol.{
  COMMAND,
  HTTP,
  TCP
}

import akka.actor.{ Actor, ActorLogging }
import akka.util.Timeout

import spray.http._
import spray.client.pipelining._

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.{ Success, Failure }

import java.net.{ Socket, InetSocketAddress }

class HealthCheckWorkerActor extends Actor with ActorLogging {

  import HealthCheckWorker._

  implicit val system = context.system
  import context.dispatcher // execution context for futures

  def receive: Receive = {
    case HealthCheckJob(task, check) =>
      val replyTo = sender() // avoids closing over the volatile sender ref

      val replyWithHealth = doCheck(task, check)

      replyWithHealth.onComplete {
        case Success(result) => replyTo ! result
        case Failure(t) =>
          replyTo ! Unhealthy(
            task.getId,
            task.getVersion,
            s"${t.getClass.getSimpleName}: ${t.getMessage}"
          )

          context stop self
      }
  }

  def doCheck(task: MarathonTask, check: HealthCheck): Future[HealthResult] =
    task.getPortsList.asScala.lift(check.portIndex) match {
      case None =>
        Future { Unhealthy(task.getId, task.getVersion, "Invalid port index") }

      case Some(port) => check.protocol match {
        case HTTP => http(task, check, port)
        case TCP  => tcp(task, check, port)
        case COMMAND =>
          Future.failed {
            val message = s"COMMAND health checks can only be performed " +
              "by the Mesos executor."
            log.warning(message)
            new UnsupportedOperationException(message)
          }
        case _ =>
          Future.failed {
            val message = s"Unknown health check protocol: [${check.protocol}]"
            log.warning(message)
            new UnsupportedOperationException(message)
          }
      }
    }

  def http(task: MarathonTask, check: HealthCheck, port: Int): Future[HealthResult] = {
    val host = task.getHost
    val rawPath = check.path.getOrElse("")
    val absolutePath = if (rawPath.startsWith("/")) rawPath else s"/$rawPath"
    val url = s"http://$host:$port$absolutePath"
    log.debug("Checking the health of [{}] via HTTP", url)

    def get(url: String): Future[HttpResponse] = {
      implicit val requestTimeout = Timeout(check.timeout)
      val pipeline: HttpRequest => Future[HttpResponse] = sendReceive
      pipeline(Get(url))
    }

    get(url).map { response =>
      if (acceptableResponses contains response.status.intValue)
        Healthy(task.getId, task.getVersion)
      else
        Unhealthy(task.getId, task.getVersion, response.status.toString())
    }
  }

  def tcp(task: MarathonTask, check: HealthCheck, port: Int): Future[HealthResult] = {
    val host = task.getHost
    val address = s"$host:$port"
    val timeoutMillis = check.timeout.toMillis.toInt
    log.debug("Checking the health of [{}] via TCP", address)

    Future {
      val address = new InetSocketAddress(host, port)
      val socket = new Socket
      socket.connect(address, timeoutMillis)
      socket.close()
      Healthy(task.getId, task.getVersion, Timestamp.now())
    }
  }

}

object HealthCheckWorker {

  // Similar to AWS R53, we accept all responses in [200, 399]
  protected[health] val acceptableResponses = Range(200, 400)

  case class HealthCheckJob(task: MarathonTask, check: HealthCheck)
}
