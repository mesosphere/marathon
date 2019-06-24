package mesosphere.marathon
package core.launchqueue.impl

import akka.actor.{Actor, Props, Stash, Status}
import akka.event.LoggingReceive
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.InstanceChangeOrSnapshot
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.{Counter, Metrics}
import org.apache.mesos.Protos.FrameworkInfo

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

sealed trait Op
case object Revive extends Op
case object Suppress extends Op

/**
  *
  * @param metrics
  * @param initialFrameworkInfo - The initial frameworkInfo used, including the frameworkId received by the registered event
  * @param defaultMesosRole - The default Mesos role as specified by --mesos_role
  * @param minReviveOffersInterval
  * @param instanceUpdates
  * @param rateLimiterUpdates
  * @param driverHolder
  */
class ReviveOffersActor(
    metrics: Metrics,
    initialFrameworkInfo: Future[FrameworkInfo],
    defaultMesosRole: String,
    minReviveOffersInterval: FiniteDuration,
    instanceUpdates: InstanceTracker.InstanceUpdates,
    rateLimiterUpdates: Source[RateLimiter.DelayUpdate, NotUsed],
    driverHolder: MarathonSchedulerDriverHolder,
    enableSuppress: Boolean) extends Actor with Stash with StrictLogging {

  private[this] val reviveCountMetric: Counter = metrics.counter("mesos.calls.revive")
  private[this] val suppressCountMetric: Counter = metrics.counter("mesos.calls.suppress")

  import context.dispatcher
  implicit val mat = ActorMaterializer()(context)

  private def frameworkInfoWithRoles(frameworkInfo: FrameworkInfo, roles: Iterable[String]): FrameworkInfo = {
    val b = frameworkInfo.toBuilder
    b.clearRoles()
    b.addAllRoles(roles.asJava)
    b.build
  }

  override def preStart(): Unit = {
    super.preStart()

    val delayedConfigRefs: Source[ReviveOffersStreamLogic.DelayedStatus, NotUsed] =
      rateLimiterUpdates.via(ReviveOffersStreamLogic.activelyDelayedRefs)

    val flattenedInstanceUpdates: Source[InstanceChangeOrSnapshot, NotUsed] = instanceUpdates.flatMapConcat {
      case (snapshot, updates) =>
        Source.single[InstanceChangeOrSnapshot](snapshot).concat(updates)
    }

    val suppressReviveFlow = ReviveOffersStreamLogic.suppressAndReviveFlow(
      minReviveOffersInterval = minReviveOffersInterval,
      enableSuppress = enableSuppress)

    val done: Future[Done] = initialFrameworkInfo.flatMap { frameworkInfo =>
      flattenedInstanceUpdates.map(Left(_))
        .merge(delayedConfigRefs.map(Right(_)))
        .via(suppressReviveFlow)
        .runWith(Sink.foreach {
          case Revive =>
            reviveCountMetric.increment()
            logger.info("Sending revive")
            driverHolder.driver.foreach { d =>
              d.reviveOffers(Seq(defaultMesosRole).asJava)
            }

          case Suppress =>
            if (enableSuppress) {
              suppressCountMetric.increment()
              logger.info("Sending suppress")
              driverHolder.driver.foreach { d =>
                val newInfo = frameworkInfoWithRoles(frameworkInfo, Seq(defaultMesosRole))
                d.updateFramework(newInfo, Seq(defaultMesosRole).asJava)
              }
            }
        })
    }

    done.pipeTo(self)
  }

  override def receive: Receive = LoggingReceive {
    case Status.Failure(ex) =>
      logger.error("Unexpected termination of revive stream", ex)
      throw ex
    case Done =>
      logger.error(s"Unexpected successful termination of revive stream")
  }

}

object ReviveOffersActor {
  def props(
    metrics: Metrics,
    initialFrameworkInfo: Future[FrameworkInfo],
    defaultMesosRole: String,
    minReviveOffersInterval: FiniteDuration,
    instanceUpdates: InstanceTracker.InstanceUpdates,
    rateLimiterUpdates: Source[RateLimiter.DelayUpdate, NotUsed],
    driverHolder: MarathonSchedulerDriverHolder,
    enableSuppress: Boolean): Props = {
    Props(new ReviveOffersActor(metrics, initialFrameworkInfo, defaultMesosRole, minReviveOffersInterval, instanceUpdates, rateLimiterUpdates, driverHolder, enableSuppress))
  }
}
