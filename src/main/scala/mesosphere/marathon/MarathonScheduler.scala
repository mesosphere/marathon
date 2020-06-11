package mesosphere.marathon

import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.event.{SchedulerRegisteredEvent, _}
import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.state.{FaultDomain, Region, Zone}
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import scala.jdk.CollectionConverters._
import mesosphere.marathon.util.SemanticVersion
import mesosphere.mesos.LibMesos
import mesosphere.util.state.{FrameworkId, MesosLeaderInfo}
import org.apache.mesos.Protos._
import org.apache.mesos.{Scheduler, SchedulerDriver}

import scala.concurrent._
import scala.util.control.NonFatal

class MarathonScheduler(
    eventBus: EventStream,
    offerProcessor: OfferProcessor,
    taskStatusProcessor: TaskStatusUpdateProcessor,
    frameworkIdRepository: FrameworkIdRepository,
    mesosLeaderInfo: MesosLeaderInfo,
    config: MarathonConf,
    crashStrategy: CrashStrategy,
    frameworkIdPromise: Promise[FrameworkID]) extends Scheduler with StrictLogging {

  private var lastMesosMasterVersion: Option[SemanticVersion] = Option.empty
  @volatile private[this] var localFaultDomain: Option[FaultDomain] = Option.empty

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val zkTimeout = config.zkTimeoutDuration

  override def registered(
    driver: SchedulerDriver,
    frameworkId: FrameworkID,
    master: MasterInfo): Unit = {
    logger.info(s"Registered as ${frameworkId.getValue} to master '${master.getId}'")
    masterVersionCheck(master)
    updateLocalFaultDomain(master)
    Await.result(frameworkIdRepository.store(FrameworkId.fromProto(frameworkId)), zkTimeout)
    mesosLeaderInfo.onNewMasterInfo(master)
    eventBus.publish(SchedulerRegisteredEvent(frameworkId.getValue, master.getHostname))
    frameworkIdPromise.trySuccess(frameworkId)
  }

  override def reregistered(driver: SchedulerDriver, master: MasterInfo): Unit = {
    logger.info("Re-registered to %s".format(master))
    masterVersionCheck(master)
    updateLocalFaultDomain(master)
    mesosLeaderInfo.onNewMasterInfo(master)
    eventBus.publish(SchedulerReregisteredEvent(master.getHostname))
  }

  override def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]): Unit = {
    offers.asScala.foreach { offer =>
      val processFuture = offerProcessor.processOffer(offer)
      processFuture.onComplete {
        case scala.util.Success(_) => logger.debug(s"Finished processing offer '${offer.getId.getValue}'")
        case scala.util.Failure(NonFatal(e)) => logger.error(s"while processing offer '${offer.getId.getValue}'", e)
      }
    }
  }

  override def offerRescinded(driver: SchedulerDriver, offer: OfferID): Unit = {
    logger.info("Offer %s rescinded".format(offer))
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
    logger.info(s"Received status update for task ${status.getTaskId.getValue}: " +
      s"${status.getState} (${status.getMessage}, healthy: ${status.getHealthy})")

    // Mesos may produce a status update with really long messages on some cases. We need to restrict
    // the message length to something we can actually store in a ZK node
    val newStatus = if (status.hasMessage && status.getMessage.length > MarathonScheduler.MAX_STATUS_MESSAGE_LENGTH) {
      logger.warn(s"Status update had length of ${status.getMessage.length}, longer than allowed ${MarathonScheduler.MAX_STATUS_MESSAGE_LENGTH} characters. Using only substring")
      status.toBuilder.setMessage(status.getMessage.substring(0, MarathonScheduler.MAX_STATUS_MESSAGE_LENGTH)).build()
    } else {
      status
    }

    taskStatusProcessor.publish(newStatus).failed.foreach {
      case NonFatal(e) =>
        logger.error(s"while processing task status update $status", e)
    }
  }

  override def frameworkMessage(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    message: Array[Byte]): Unit = {
    logger.info(s"Received framework message $executor $slave $message")
    eventBus.publish(MesosFrameworkMessageEvent(executor.getValue, slave.getValue, message))
  }

  override def disconnected(driver: SchedulerDriver): Unit = {
    logger.warn("Disconnected")

    eventBus.publish(SchedulerDisconnectedEvent())

    // stop the driver. this avoids ambiguity and delegates leadership-abdication responsibility.
    // this helps to clarify responsibility during leadership transitions: currently the
    // **scheduler service** is responsible for integrating with leadership election.
    // @see MarathonSchedulerService.startLeadership
    driver.stop(true)
  }

  override def slaveLost(driver: SchedulerDriver, slave: SlaveID): Unit = {
    logger.info(s"Lost slave $slave")
  }

  override def executorLost(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    p4: Int): Unit = {
    logger.info(s"Lost executor $executor slave $p4")
  }

  override def error(driver: SchedulerDriver, message: String): Unit = {
    logger.warn(s"Error: $message\n" +
      "In case Mesos does not allow registration with the current frameworkId, " +
      "follow the instructions for recovery here: https://mesosphere.github.io/marathon/docs/framework-id.html")

    if (message.contains("Framework has been removed")) {
      crashStrategy.crash(CrashStrategy.FrameworkHasBeenRemoved)
    } else {
      crashStrategy.crash(CrashStrategy.MesosSchedulerError)
    }
  }

  /**
    * Verifies that the Mesos Master we connected to meets our minimum
    * required version.
    *
    * If the minimum version is not met, then we log an error and
    * suicide.
    *
    * @param masterInfo Contains the version reported by the master.
    */
  protected def masterVersionCheck(masterInfo: MasterInfo): Unit = {
    val masterVersion = masterInfo.getVersion
    logger.info(s"Mesos Master version $masterVersion")
    lastMesosMasterVersion = SemanticVersion(masterVersion)
    if (!LibMesos.masterCompatible(masterVersion)) {
      logger.error(s"Mesos Master version $masterVersion does not meet minimum required version ${LibMesos.MesosMasterMinimumVersion}")
      crashStrategy.crash(CrashStrategy.MesosSchedulerError)
    }
  }

  protected def updateLocalFaultDomain(masterInfo: MasterInfo): Unit = {
    if (masterInfo.hasDomain && masterInfo.getDomain.hasFaultDomain) {
      localFaultDomain = Some(FaultDomain(
        Region(masterInfo.getDomain.getFaultDomain.getRegion.getName),
        Zone(masterInfo.getDomain.getFaultDomain.getZone.getName)
      ))
    } else {
      localFaultDomain = None
    }
  }

  /** The last version of the Mesos master */
  def mesosMasterVersion(): Option[SemanticVersion] = lastMesosMasterVersion

  /**
    * Current local region where Mesos master is running
    * @return region if it's available, None otherwise
    */
  def getLocalRegion: Option[Region] = localFaultDomain.map(_.region)
}

object MarathonScheduler {
  val MAX_STATUS_MESSAGE_LENGTH = 10000
}