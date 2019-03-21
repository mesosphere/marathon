package mesosphere.marathon
package core.health.impl

import java.net.{ InetSocketAddress, Socket }

import akka.actor.{ Actor, PoisonPill }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Materializer }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.util.ThreadPoolContext

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class HealthCheckWorkerActor(implicit mat: Materializer) extends Actor with StrictLogging {

  import HealthCheckWorker._

  private implicit val system = context.system
  private implicit val scheduler = system.scheduler
  import context.dispatcher // execution context for futures
  private implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))

  def receive: Receive = {
    case HealthCheckJob(app, instance, check) =>
      val replyTo = sender() // avoids closing over the volatile sender ref

      doCheck(app, instance, check)
        .andThen {
          case Success(Some(result)) => replyTo ! result
          case Success(None) => // ignore
          case Failure(t) =>
            logger.warn(s"Performing health check for app=${app.id} instance=${instance.instanceId} port=${check.port} failed with exception", t)
            replyTo ! Unhealthy(
              instance.instanceId,
              instance.runSpecVersion,
              s"${t.getClass.getSimpleName}: ${t.getMessage}"
            )
        }
        .onComplete { _ => self ! PoisonPill }
  }

  def doCheck(
    app: AppDefinition, instance: Instance, check: MarathonTcpHealthCheck): Future[Option[HealthResult]] = {
    // HealthChecks are only supported for legacy App instances with exactly one task
    val effectiveIpAddress = instance.appTask.status.networkInfo.effectiveIpAddress(app)
    effectiveIpAddress match {
      case Some(host) =>
        val maybePort = check.effectivePort(app, instance)
        maybePort match {
          case Some(port) => tcp(instance, check, host, port)
          case None => Future.failed {
            val message = "Health check failed: unable to get the task's effectivePort"
            logger.warn(message)
            new UnsupportedOperationException(message)
          }
        }
      case None =>
        Future.failed {
          val message = "Health check failed: unable to get the task's effective IP address"
          logger.warn(message)
          new UnsupportedOperationException(message)
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
    logger.debug(s"Checking the health of [$address] for instance=${instance.instanceId} via TCP")

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
}

@SuppressWarnings(Array("NullParameter"))
object HealthCheckWorker {

  case class HealthCheckJob(app: AppDefinition, instance: Instance, check: MarathonTcpHealthCheck)
}
