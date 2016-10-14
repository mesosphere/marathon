package mesosphere.marathon.core.heartbeat

import java.util.UUID
import javax.inject.{ Inject, Named }

import akka.actor.ActorRef
import mesosphere.marathon.ModuleNames
import org.apache.mesos.{ Scheduler, SchedulerDriver }
import org.apache.mesos.Protos._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/**
  * @constructor create a mesos Scheduler decorator that intercepts callbacks from a mesos SchedulerDriver,
  * translating each callback into some kind of HeartbeatMonitor.Message that is asynchronously delivered to a
  * heartbeatActor. All callback parameters are passed through, unchanged, to the given delegate scheduler
  * implementation.
  *
  * @param scheduler is the delegate scheduler implementation
  * @param heartbeatActor is the receipient of generated Heartbeat.Message's
  *
  * @see mesosphere.util.monitor.HeartbeatMonitor
  * @see org.apache.mesos.Scheduler
  * @see org.apache.mesos.SchedulerDriver
  */
class MesosHeartbeatMonitor @Inject() (
    @Named(MesosHeartbeatMonitor.BASE) scheduler: Scheduler,
    @Named(ModuleNames.MESOS_HEARTBEAT_ACTOR) heartbeatActor: ActorRef
) extends Scheduler {

  import MesosHeartbeatMonitor._

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  log.debug(s"created mesos heartbeat monitor for scheduler $scheduler")

  protected[marathon] def heartbeatReactor(driver: SchedulerDriver): Heartbeat.Reactor = new Heartbeat.Reactor {
    // virtualHeartbeatTasks is sent in a reconciliation message to mesos in order to force a
    // predictable response: the master (if we're connected) will send back a TASK_LOST because
    // the fake task ID and agent ID that we use will never actually exist in the cluster.
    // this is part of a short-term workaround: will no longer be needed once marathon is ported
    // to use the new mesos v1 http API.
    private[this] val virtualHeartbeatTasks: java.util.Collection[TaskStatus] = Seq(fakeHeartbeatStatus).asJava

    override def onSkip(skipped: Int): Unit = {
      // the first skip (skipped == 1) may be because there simply haven't been any offers or task status updates
      // sent by the master within the heartbeat interval. that's completely normal, so we only log if skipped > 1
      // because that means that we've prompted mesos via task reconciliation and it still hasn't responded in a
      // timely manner.
      if (skipped > 1) {
        log.info(s"missed ${skipped - 1} expected heartbeat(s) from mesos master; possibly disconnected")
      }
      log.debug("Prompting mesos for a heartbeat via explicit task reconciliation")
      driver.reconcileTasks(virtualHeartbeatTasks)
    }

    override def onFailure(): Unit = {
      log.warn("Too many subsequent heartbeats missed; inferring disconnected from mesos master")
      disconnected(driver)
    }
  }

  override def registered(
    driver: SchedulerDriver,
    frameworkId: FrameworkID,
    master: MasterInfo): Unit = {
    log.debug("registered heartbeat monitor")
    heartbeatActor ! Heartbeat.MessageActivate(heartbeatReactor(driver), sessionOf(driver))
    scheduler.registered(driver, frameworkId, master)
  }

  override def reregistered(driver: SchedulerDriver, master: MasterInfo): Unit = {
    log.debug("reregistered heartbeat monitor")
    heartbeatActor ! Heartbeat.MessageActivate(heartbeatReactor(driver), sessionOf(driver))
    scheduler.reregistered(driver, master)
  }

  override def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]): Unit = {
    heartbeatActor ! Heartbeat.MessagePulse
    scheduler.resourceOffers(driver, offers)
  }

  override def offerRescinded(driver: SchedulerDriver, offer: OfferID): Unit = {
    heartbeatActor ! Heartbeat.MessagePulse
    scheduler.offerRescinded(driver, offer)
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
    heartbeatActor ! Heartbeat.MessagePulse

    // filter TASK-LOST updates for our fake tasks to avoid cluttering the logs
    if (!isFakeHeartbeatUpdate(status)) {
      scheduler.statusUpdate(driver, status)
    } else {
      log.debug("received fake heartbeat task-status update")
    }
  }

  protected[marathon] def isFakeHeartbeatUpdate(status: TaskStatus): Boolean =
    status.getState == TaskState.TASK_LOST &&
      status.hasSource && status.getSource == TaskStatus.Source.SOURCE_MASTER &&
      status.hasReason && status.getReason == TaskStatus.Reason.REASON_RECONCILIATION &&
      status.hasSlaveId && status.getSlaveId.getValue.startsWith(FAKE_AGENT_PREFIX) &&
      status.hasTaskId && status.getTaskId.getValue.startsWith(FAKE_TASK_PREFIX)

  override def frameworkMessage(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    message: Array[Byte]): Unit = {
    heartbeatActor ! Heartbeat.MessagePulse
    scheduler.frameworkMessage(driver, executor, slave, message)
  }

  override def disconnected(driver: SchedulerDriver): Unit = {
    // heartbeatReactor may have triggered this, but that's ok because if it did then
    // it's already "inactive", so this becomes a no-op
    log.debug("disconnected heartbeat monitor")
    heartbeatActor ! Heartbeat.MessageDeactivate(sessionOf(driver))
    scheduler.disconnected(driver)
  }

  override def slaveLost(driver: SchedulerDriver, slave: SlaveID): Unit = {
    heartbeatActor ! Heartbeat.MessagePulse
    scheduler.slaveLost(driver, slave)
  }

  override def executorLost(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    code: Int): Unit = {
    heartbeatActor ! Heartbeat.MessagePulse
    scheduler.executorLost(driver, executor, slave, code)
  }

  override def error(driver: SchedulerDriver, message: String): Unit = {
    // errors from the driver are fatal (to the driver) so it should be safe to deactivate here because
    // the marathon scheduler **should** either exit or else create a new driver instance and reregister.
    log.debug("errored heartbeat monitor")
    heartbeatActor ! Heartbeat.MessageDeactivate(sessionOf(driver))
    scheduler.error(driver, message)
  }
}

object MesosHeartbeatMonitor {
  final val BASE = "mesosHeartbeatMonitor.base"

  final val DEFAULT_HEARTBEAT_INTERVAL_MS = 15000L
  final val DEFAULT_HEARTBEAT_FAILURE_THRESHOLD = 5

  final val FAKE_TASK_PREFIX = "fake-marathon-pacemaker-task-"
  final val FAKE_AGENT_PREFIX = "fake-marathon-pacemaker-agent-"

  /** @return a uniquely identifying token for the current session */
  def sessionOf(driver: SchedulerDriver): AnyRef =
    // a new driver is instantiated for each session already so we can just use the driver instance
    // as the session token. it feels a bit hacky but does the job. would rather hack this in one place
    // vs everywhere else that wants the session token.
    driver

  protected[marathon] def fakeHeartbeatStatus =
    TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue(FAKE_TASK_PREFIX + UUID.randomUUID().toString))
      .setState(TaskState.TASK_LOST) // required, so we just need to set something
      .setSlaveId(SlaveID.newBuilder.setValue(FAKE_AGENT_PREFIX + UUID.randomUUID().toString))
      .build
}
