package mesosphere.marathon.health

import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol.{HTTP, TCP}

import akka.actor.{Actor, ActorLogging}
import akka.util.Timeout

import spray.http._
import spray.client.pipelining._

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.{Success, Failure}

import java.net.{Socket, InetSocketAddress}


class HealthCheckWorkerActor extends Actor with ActorLogging {

  import HealthCheckWorker._

  implicit val system = context.system
  import context.dispatcher // execution context for futures

  def receive = {
    case HealthCheckJob(task, check) =>
      val replyTo = sender // avoids closing over the volatile sender ref

      val replyWithHealth = doCheck(task, check)

      replyWithHealth.onComplete {
        case Success(result) => replyTo ! result
        case Failure(t) => replyTo ! Unhealthy(
          task.getId,
          Timestamp.now(),
          s"${t.getClass.getSimpleName}: ${t.getMessage}"
        )

      context stop self
    }
  }

  def doCheck(task: MarathonTask, check: HealthCheck): Future[HealthResult] =
    task.getPortsList.asScala.lift(check.portIndex) match {
      case None =>
        Future { Unhealthy(task.getId, Timestamp.now(), "Invalid port index") }

      case Some(port) => check.protocol match {
        case HTTP => http(task, check, port)
        case TCP  => tcp(task, check, port)
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
        Healthy(task.getId, Timestamp.now())
      else
        Unhealthy(task.getId, Timestamp.now(), response.status.toString())
    }
  }

  def tcp(task: MarathonTask, check: HealthCheck, port: Int): Future[HealthResult] = {
    val host = task.getHost
    val address = s"$host:$port"
    val timeoutMillis = check.timeout.toMillis.toInt
    log.debug("Checking the health of [{}] via TCP", address)

    Future {
      val address = InetSocketAddress.createUnresolved(host, port)
      val socket = new Socket
      socket.connect(address, timeoutMillis)
      socket.close()
      Healthy(task.getId, Timestamp.now())
    }
  }

}

object HealthCheckWorker {

  // Similar to AWS R53, we accept all responses in [200, 399]
  protected[health] val acceptableResponses = Range(200, 400)

  case class HealthCheckJob(task: MarathonTask, check: HealthCheck)

  sealed trait HealthResult {
    val taskId: String
    val time: Timestamp
  }

  case class Healthy(
    taskId: String,
    time: Timestamp = Timestamp.now()
  ) extends HealthResult

  case class Unhealthy(
    taskId: String,
    time: Timestamp = Timestamp.now(),
    cause: String
  ) extends HealthResult

}
