package mesosphere.marathon
package core.health.impl

import java.net.{ InetSocketAddress, Socket }
import java.security.cert.X509Certificate
import javax.net.ssl.{ KeyManager, SSLContext, X509TrustManager }

import akka.actor.{ Actor, PoisonPill }
import akka.util.Timeout
import mesosphere.marathon.core.health._
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.util.ThreadPoolContext
import spray.client.pipelining._
import spray.http._

import scala.concurrent.Future
import scala.util.{ Failure, Success }
import mesosphere.marathon.Protos
import mesosphere.marathon.core.instance.Instance
import org.slf4j.LoggerFactory

class HealthCheckWorkerActor extends Actor {

  import HealthCheckWorker._

  implicit val system = context.system
  import context.dispatcher // execution context for futures

  private[this] val log = LoggerFactory.getLogger(getClass)

  def receive: Receive = {
    case HealthCheckJob(app, instance, check) =>
      val replyTo = sender() // avoids closing over the volatile sender ref

      doCheck(app, instance, check)
        .andThen {
          case Success(Some(result)) => replyTo ! result
          case Success(None) => // ignore
          case Failure(t) =>
            log.debug("Performing health check failed with exception", t)
            replyTo ! Unhealthy(
              instance.instanceId,
              instance.runSpecVersion,
              s"${t.getClass.getSimpleName}: ${t.getMessage}"
            )
        }
        .onComplete { _ => self ! PoisonPill }
  }

  def doCheck(
    app: AppDefinition, instance: Instance, check: MarathonHealthCheck): Future[Option[HealthResult]] = {
    // HealthChecks are only supported for legacy App instances with exactly one task
    val effectiveIpAddress = instance.appTask.status.networkInfo.effectiveIpAddress(app)
    effectiveIpAddress match {
      case Some(host) =>
        val port = check.effectivePort(app, instance)
        check match {
          case hc: MarathonHttpHealthCheck =>
            hc.protocol match {
              case Protos.HealthCheckDefinition.Protocol.HTTPS => https(instance, hc, host, port)
              case Protos.HealthCheckDefinition.Protocol.HTTP => http(instance, hc, host, port)
              case invalidProtocol: Protos.HealthCheckDefinition.Protocol =>
                Future.failed {
                  val message = s"Health check failed: HTTP health check contains invalid protocol: $invalidProtocol"
                  log.warn(message)
                  new UnsupportedOperationException(message)
                }
            }
          case hc: MarathonTcpHealthCheck => tcp(instance, hc, host, port)
        }
      case None =>
        Future.failed {
          val message = "Health check failed: unable to get the task's effective IP address"
          log.warn(message)
          new UnsupportedOperationException(message)
        }
    }
  }

  def http(
    instance: Instance,
    check: MarathonHttpHealthCheck,
    host: String,
    port: Int): Future[Option[HealthResult]] = {
    val rawPath = check.path.getOrElse("")
    val absolutePath = if (rawPath.startsWith("/")) rawPath else s"/$rawPath"
    val url = s"http://$host:$port$absolutePath"
    log.debug(s"Checking the health of [$url] via HTTP")

    def get(url: String): Future[HttpResponse] = {
      implicit val requestTimeout = Timeout(check.timeout)
      val pipeline: HttpRequest => Future[HttpResponse] = sendReceive
      pipeline(Get(url))
    }

    get(url).map { response =>
      if (acceptableResponses contains response.status.intValue)
        Some(Healthy(instance.instanceId, instance.runSpecVersion))
      else if (check.ignoreHttp1xx && (toIgnoreResponses contains response.status.intValue)) {
        log.debug(s"Ignoring health check HTTP response ${response.status.intValue} for ${instance.instanceId}")
        None
      } else {
        log.debug("Health check for {} responded with {}", instance.instanceId, response.status: Any)
        Some(Unhealthy(instance.instanceId, instance.runSpecVersion, response.status.toString()))
      }
    }
  }

  def tcp(
    instance: Instance,
    check: MarathonTcpHealthCheck,
    host: String,
    port: Int): Future[Option[HealthResult]] = {
    val address = s"$host:$port"
    val timeoutMillis = check.timeout.toMillis.toInt
    log.debug(s"Checking the health of [$address] via TCP")

    Future {
      val address = new InetSocketAddress(host, port)
      val socket = new Socket
      scala.concurrent.blocking {
        socket.connect(address, timeoutMillis)
        socket.close()
      }
      Some(Healthy(instance.instanceId, instance.runSpecVersion, Timestamp.now()))
    }(ThreadPoolContext.ioContext)
  }

  def https(
    instance: Instance,
    check: MarathonHttpHealthCheck,
    host: String,
    port: Int): Future[Option[HealthResult]] = {
    val rawPath = check.path.getOrElse("")
    val absolutePath = if (rawPath.startsWith("/")) rawPath else s"/$rawPath"
    val url = s"https://$host:$port$absolutePath"
    log.debug(s"Checking the health of [$url] via HTTPS")

    @SuppressWarnings(Array("NullParameter"))
    def get(url: String): Future[HttpResponse] = {
      implicit val requestTimeout = Timeout(check.timeout)
      implicit def trustfulSslContext: SSLContext = {
        object BlindFaithX509TrustManager extends X509TrustManager {
          @SuppressWarnings(Array("EmptyMethod"))
          def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
          @SuppressWarnings(Array("EmptyMethod"))
          def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
          def getAcceptedIssuers: Array[X509Certificate] = Array[X509Certificate]()
        }

        val context = SSLContext.getInstance("Default")
        context.init(Array[KeyManager](), Array(BlindFaithX509TrustManager), null)
        context
      }
      val pipeline: HttpRequest => Future[HttpResponse] = sendReceive
      pipeline(Get(url))
    }

    get(url).map { response =>
      if (acceptableResponses contains response.status.intValue) {
        Some(Healthy(instance.instanceId, instance.runSpecVersion))
      } else {
        log.debug("Health check for {} responded with {}", instance.instanceId, response.status: Any)
        Some(Unhealthy(instance.instanceId, instance.runSpecVersion, response.status.toString()))
      }
    }
  }

}

object HealthCheckWorker {

  // Similar to AWS R53, we accept all responses in [200, 399]
  protected[health] val acceptableResponses = Range(200, 400)
  protected[health] val toIgnoreResponses = Range(100, 200)

  case class HealthCheckJob(app: AppDefinition, instance: Instance, check: MarathonHealthCheck)
}
