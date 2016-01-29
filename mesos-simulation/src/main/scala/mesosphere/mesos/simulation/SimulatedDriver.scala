package mesosphere.mesos.simulation

import java.util

import akka.actor.{ ActorRef, ActorSystem, Props }
import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.mesos.Protos._
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

/**
  * The facade to the mesos simulation.
  *
  * It starts/stops a new actor system for the simulation when the corresponding life-cycle methods of the
  * [[org.apache.mesos.SchedulerDriver]] interface are called.
  *
  * The implemented commands of the driver interface are forwarded as messages to the
  * [[mesosphere.mesos.simulation.DriverActor]].
  * Unimplemented methods throw [[scala.NotImplementedError]]s.
  */
class SimulatedDriver(driverProps: Props) extends SchedulerDriver {

  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] def driverCmd(cmd: AnyRef): Status = {
    driverActorRefOpt match {
      case Some(driverActor) =>
        log.debug(s"send driver cmd $cmd")
        driverActor ! cmd
      case None =>
        log.debug("no driver actor configured")
    }
    status
  }

  override def declineOffer(offerId: OfferID): Status =
    driverCmd(DriverActor.DeclineOffer(offerId))

  override def launchTasks(offerIds: util.Collection[OfferID], tasks: util.Collection[TaskInfo]): Status =
    driverCmd(DriverActor.LaunchTasks(offerIds.toSeq, tasks.toSeq))

  // Mesos 0.23.x
  override def acceptOffers(
    offerIds: util.Collection[OfferID], ops: util.Collection[Offer.Operation], filters: Filters): Status =
    driverCmd(DriverActor.AcceptOffers(offerIds.toSeq, ops.toSeq, filters))

  override def killTask(taskId: TaskID): Status = driverCmd(DriverActor.KillTask(taskId))
  override def reconcileTasks(statuses: util.Collection[TaskStatus]): Status = {
    driverCmd(DriverActor.ReconcileTask(statuses.toSeq))
  }

  override def suppressOffers(): Status = driverCmd(DriverActor.SuppressOffers)

  override def reviveOffers(): Status = driverCmd(DriverActor.ReviveOffers)

  override def declineOffer(offerId: OfferID, filters: Filters): Status = Status.DRIVER_RUNNING

  override def launchTasks(offerIds: util.Collection[OfferID], tasks: util.Collection[TaskInfo],
                           filters: Filters): Status = ???
  override def launchTasks(offerId: OfferID, tasks: util.Collection[TaskInfo], filters: Filters): Status = ???
  override def launchTasks(offerId: OfferID, tasks: util.Collection[TaskInfo]): Status = ???
  override def requestResources(requests: util.Collection[Request]): Status = ???
  override def sendFrameworkMessage(executorId: ExecutorID, slaveId: SlaveID, data: Array[Byte]): Status = ???
  override def acknowledgeStatusUpdate(ackStatus: TaskStatus): Status = status

  // life cycle

  @volatile
  var system: Option[ActorSystem] = None
  @volatile
  var driverActorRefOpt: Option[ActorRef] = None

  private def status: Status = system match {
    case None    => Status.DRIVER_STOPPED
    case Some(_) => Status.DRIVER_RUNNING
  }

  override def start(): Status = {
    log.info("Starting simulated Mesos")
    val config: Config = ConfigFactory.load(getClass.getClassLoader, "mesos-simulation.conf")
    val sys: ActorSystem = ActorSystem("mesos-simulation", config)
    system = Some(sys)
    driverActorRefOpt = Some(sys.actorOf(driverProps, "driver"))
    driverCmd(this)

    Status.DRIVER_RUNNING
  }

  override def stop(failover: Boolean): Status = stop()
  override def stop(): Status = abort()
  override def abort(): Status = {
    system match {
      case None => Status.DRIVER_NOT_STARTED
      case Some(sys) =>
        sys.shutdown()
        Status.DRIVER_ABORTED
    }
  }

  override def run(): Status = {
    start()
    join()
  }

  override def join(): Status = {
    system match {
      case None => Status.DRIVER_NOT_STARTED
      case Some(sys) =>
        sys.awaitTermination()
        driverActorRefOpt = None
        system = None
        log.info("Stopped simulated Mesos")
        Status.DRIVER_STOPPED
    }
  }
}
