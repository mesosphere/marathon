package mesosphere.marathon

import akka.event.EventStream
import mesosphere.marathon.core.base._
import mesosphere.marathon.core.event.{ SchedulerRegisteredEvent, _ }
import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.state.{ FaultDomain, Region, Zone }
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.util.SemanticVersion
import mesosphere.mesos.LibMesos
import mesosphere.util.state.{ FrameworkId, MesosLeaderInfo }
import org.apache.mesos.Protos._
import org.apache.mesos.{ Scheduler, SchedulerDriver }
import org.slf4j.LoggerFactory

import scala.concurrent._
import scala.util.control.NonFatal

class MarathonScheduler(
    eventBus: EventStream,
    offerProcessor: OfferProcessor,
    taskStatusProcessor: TaskStatusUpdateProcessor,
    frameworkIdRepository: FrameworkIdRepository,
    mesosLeaderInfo: MesosLeaderInfo,
    config: MarathonConf) extends Scheduler {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  private var lastMesosMasterVersion: Option[SemanticVersion] = Option.empty
  @volatile private[this] var localFaultDomain: Option[FaultDomain] = Option.empty

  import mesosphere.marathon.core.async.ExecutionContexts.global

  implicit val zkTimeout = config.zkTimeoutDuration

  override def registered(
    driver: SchedulerDriver,
    frameworkId: FrameworkID,
    master: MasterInfo): Unit = {
    log.info(s"Registered as ${frameworkId.getValue} to master '${master.getId}'")
    masterVersionCheck(master)
    updateLocalFaultDomain(master)
    Await.result(frameworkIdRepository.store(FrameworkId.fromProto(frameworkId)), zkTimeout)
    mesosLeaderInfo.onNewMasterInfo(master)
    eventBus.publish(SchedulerRegisteredEvent(frameworkId.getValue, master.getHostname))
  }

  override def reregistered(driver: SchedulerDriver, master: MasterInfo): Unit = {
    log.info("Re-registered to %s".format(master))
    masterVersionCheck(master)
    updateLocalFaultDomain(master)
    mesosLeaderInfo.onNewMasterInfo(master)
    eventBus.publish(SchedulerReregisteredEvent(master.getHostname))
  }

  override def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]): Unit = {
    offers.foreach { offer =>
      val processFuture = offerProcessor.processOffer(offer)
      processFuture.onComplete {
        case scala.util.Success(_) => log.debug(s"Finished processing offer '${offer.getId.getValue}'")
        case scala.util.Failure(NonFatal(e)) => log.error(s"while processing offer '${offer.getId.getValue}'", e)
      }
    }
  }

  override def offerRescinded(driver: SchedulerDriver, offer: OfferID): Unit = {
    log.info("Offer %s rescinded".format(offer))
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
    log.info("Received status update for task %s: %s (%s)"
      .format(status.getTaskId.getValue, status.getState, status.getMessage))

    taskStatusProcessor.publish(status).failed.foreach {
      case NonFatal(e) =>
        log.error(s"while processing task status update $status", e)
    }
  }

  override def frameworkMessage(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    message: Array[Byte]): Unit = {
    log.info(s"Received framework message $executor $slave $message")
    eventBus.publish(MesosFrameworkMessageEvent(executor.getValue, slave.getValue, message))
  }

  override def disconnected(driver: SchedulerDriver): Unit = {
    log.warn("Disconnected")

    eventBus.publish(SchedulerDisconnectedEvent())

    // stop the driver. this avoids ambiguity and delegates leadership-abdication responsibility.
    // this helps to clarify responsibility during leadership transitions: currently the
    // **scheduler service** is responsible for integrating with leadership election.
    // @see MarathonSchedulerService.startLeadership
    driver.stop(true)
  }

  override def slaveLost(driver: SchedulerDriver, slave: SlaveID): Unit = {
    log.info(s"Lost slave $slave")
  }

  override def executorLost(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    p4: Int): Unit = {
    log.info(s"Lost executor $executor slave $p4")
  }

  override def error(driver: SchedulerDriver, message: String): Unit = {
    log.warn(s"Error: $message\n" +
      "In case Mesos does not allow registration with the current frameworkId, " +
      s"delete the ZooKeeper Node: ${config.zkPath}/state/framework:id\n" +
      "CAUTION: if you remove this node, all tasks started with the current frameworkId will be orphaned!")

    // Currently, it's pretty hard to disambiguate this error from other causes of framework errors.
    // Watch MESOS-2522 which will add a reason field for framework errors to help with this.
    // For now the frameworkId is removed based on the error message.
    val removeFrameworkId = message match {
      case "Framework has been removed" => true
      case _: String => false
    }
    suicide(removeFrameworkId)
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
    log.info(s"Mesos Master version $masterVersion")
    lastMesosMasterVersion = SemanticVersion(masterVersion)
    if (!LibMesos.masterCompatible(masterVersion)) {
      log.error(s"Mesos Master version $masterVersion does not meet minimum required version ${LibMesos.MesosMasterMinimumVersion}")
      suicide(removeFrameworkId = false)
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

  /** The last version of the mesos master */
  def mesosMasterVersion(): Option[SemanticVersion] = lastMesosMasterVersion

  /**
    * Current local region where mesos master is running
    * @return region if it's available, None otherwise
    */
  def getLocalRegion: Option[Region] = localFaultDomain.map(_.region)

  /**
    * Exits the JVM process, optionally deleting Marathon's FrameworkID
    * from the backing persistence store.
    *
    * If `removeFrameworkId` is set, the next Marathon process elected
    * leader will fail to find a stored FrameworkID and invoke `register`
    * instead of `reregister`.  This is important because on certain kinds
    * of framework errors (such as exceeding the framework failover timeout),
    * the scheduler may never re-register with the saved FrameworkID until
    * the leading Mesos master process is killed.
    */
  protected def suicide(removeFrameworkId: Boolean): Unit = {
    log.error("Committing suicide!")

    if (removeFrameworkId) Await.ready(frameworkIdRepository.delete(), config.zkTimeoutDuration)

    // Asynchronously call asyncExit to avoid deadlock due to the JVM shutdown hooks
    Runtime.getRuntime.asyncExit()
  }
}
