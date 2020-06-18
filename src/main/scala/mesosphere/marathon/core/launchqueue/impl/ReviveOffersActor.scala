package mesosphere.marathon
package core.launchqueue.impl

import akka.actor.{Actor, Props, Stash, Status}
import akka.event.LoggingReceive
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.InstanceChangeOrSnapshot
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersStreamLogic.{IssueRevive, RoleDirective, UpdateFramework}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.{Counter, Metrics}
import org.apache.mesos.Protos.FrameworkInfo

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

sealed trait RoleOfferState { def isWanted: Boolean }
case object OffersWanted extends RoleOfferState { def isWanted = true }
case object OffersNotWanted extends RoleOfferState { def isWanted = false }

/**
  * Manages revive and suppress calls to Mesos.
  *
  * @param metrics
  * @param initialFrameworkInfo - The initial frameworkInfo used, including the frameworkId received by the registered event
  * @param defaultMesosRole - The default Mesos role as specified by --mesos_role
  * @param minReviveOffersInterval - The interval between revive repeats as defined in [[MarathonConf]]
  * @param instanceUpdates - Updates from the [[InstanceTracker]]
  * @param rateLimiterUpdates - Update from the [[RateLimiterActor]]
  * @param driverHolder - Mesos driver used to call revive, suppress or update the framework info
  */
class ReviveOffersActor(
    metrics: Metrics,
    initialFrameworkInfo: Future[FrameworkInfo],
    defaultMesosRole: String,
    minReviveOffersInterval: FiniteDuration,
    instanceUpdates: InstanceTracker.InstanceUpdates,
    rateLimiterUpdates: Source[RateLimiter.DelayUpdate, NotUsed],
    driverHolder: MarathonSchedulerDriverHolder,
    enableSuppress: Boolean
) extends Actor
    with Stash
    with StrictLogging {

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
      enableSuppress = enableSuppress,
      defaultMesosRole
    )

    val done: Future[Done] = initialFrameworkInfo.flatMap { frameworkInfo =>
      flattenedInstanceUpdates
        .map(Left(_))
        .merge(delayedConfigRefs.map(Right(_)))
        .via(suppressReviveFlow)
        .via(reviveSuppressMetrics)
        .runWith(Sink.foreach {
          case UpdateFramework(roleState, _, _) =>
            driverHolder.driver.foreach { d =>
              val newInfo = frameworkInfoWithRoles(frameworkInfo, roleState.keys)
              val suppressedRoles = roleState.iterator.collect { case (role, OffersNotWanted) => role }.toSeq
              d.updateFramework(newInfo, suppressedRoles.asJava)
            }

          case IssueRevive(roles) =>
            driverHolder.driver.foreach { d =>
              d.reviveOffers(roles.asJava)
            }
        })
    }

    done.pipeTo(self)
  }

  val reviveSuppressMetrics: Flow[RoleDirective, RoleDirective, NotUsed] = Flow[RoleDirective].map {
    case directive @ UpdateFramework(newState, newlyRevived, newlySuppressed) =>
      newlyRevived.foreach { role =>
        logger.info(s"Role '${role}' newly revived via update framework call")
        reviveCountMetric.increment()
      }

      newlySuppressed.foreach { role =>
        logger.info(s"Role '${role}' newly suppressed via update framework call")
        suppressCountMetric.increment()
      }
      directive

    case directive @ IssueRevive(roles) =>
      roles.foreach { role =>
        reviveCountMetric.increment()
        logger.info(s"Role '${role}' explicitly revived")
      }

      directive
  }

  override def receive: Receive =
    LoggingReceive {
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
      enableSuppress: Boolean
  ): Props = {
    Props(
      new ReviveOffersActor(
        metrics,
        initialFrameworkInfo,
        defaultMesosRole,
        minReviveOffersInterval,
        instanceUpdates,
        rateLimiterUpdates,
        driverHolder,
        enableSuppress
      )
    )
  }
}
