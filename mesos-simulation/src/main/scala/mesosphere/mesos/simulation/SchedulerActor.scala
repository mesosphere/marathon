package mesosphere.mesos.simulation

import akka.actor.{Actor, Stash}
import akka.event.LoggingReceive
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.Protos.{FrameworkID, MasterInfo, Offer, TaskStatus}
import org.apache.mesos.{Scheduler, SchedulerDriver}

import scala.collection.immutable.Seq

object SchedulerActor extends StrictLogging {

  case class Registered(
      frameworkId: FrameworkID,
      master: MasterInfo)

  case class ResourceOffers(offers: Seq[Offer])
}

class SchedulerActor(scheduler: Scheduler) extends Actor with Stash {
  import SchedulerActor._

  var driverOpt: Option[SchedulerDriver] = None

  def receive: Receive = waitForDriver

  def waitForDriver: Receive = LoggingReceive.withLabel("waitForDriver") {
    case driver: SchedulerDriver =>
      logger.info("received driver")
      driverOpt = Some(driver)
      context.become(handleCmds(driver))
      unstashAll()

    case _ => stash()
  }

  def handleCmds(driver: SchedulerDriver): Receive = LoggingReceive.withLabel("handleCmds") {
    case Registered(frameworkId, masterInfo) =>
      scheduler.registered(driver, frameworkId, masterInfo)

    case ResourceOffers(offers) =>
      scheduler.resourceOffers(driver, offers.asJava)

    case status: TaskStatus =>
      scheduler.statusUpdate(driver, status)
  }

  override def postStop(): Unit = {
    driverOpt.foreach { driver => scheduler.disconnected(driver) }
  }
}
