package mesosphere.mesos.simulation

import akka.actor.{ Actor, ActorRef, Cancellable, Props }
import akka.event.LoggingReceive
import mesosphere.mesos.simulation.DriverActor.{ KillTask, LaunchTasks }
import org.apache.mesos.Protos._
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.concurrent.duration._

object DriverActor {
  case class DeclineOffer(offerId: OfferID)

  /**
    * Corresponds to the following method in [[org.apache.mesos.MesosSchedulerDriver]]:
    *
    * `override def launchTasks(offerIds: util.Collection[OfferID], tasks: util.Collection[TaskInfo]): Status`
    */
  case class LaunchTasks(offerIds: Seq[OfferID], tasks: Seq[TaskInfo])

  /**
    * Corresponds to the following method in [[org.apache.mesos.MesosSchedulerDriver]]:
    *
    * `override def killTask(taskId: TaskID): Status`
    */
  case class KillTask(taskId: TaskID)

  /**
    * Corresponds to the following method in [[org.apache.mesos.MesosSchedulerDriver]]:
    *
    * `override def reconcileTasks(statuses: util.Collection[TaskStatus]): Status`
    */
  case class ReconcileTask(taskStatus: Seq[TaskStatus])
}

class DriverActor(schedulerProps: Props) extends Actor {
  private val log = LoggerFactory.getLogger(getClass)

  private[this] val numberOfOffersPerCycle: Int = 10

  private[this] var periodicOffers: Option[Cancellable] = None
  private[this] var scheduler: ActorRef = _

  override def preStart(): Unit = {
    super.preStart()
    scheduler = context.actorOf(schedulerProps, "scheduler")
    def resource(name: String, value: Double): Resource = {
      Resource.newBuilder()
        .setName(name)
        .setType(Value.Type.SCALAR)
        .setScalar(Value.Scalar.newBuilder().setValue(value))
        .build()
    }

    val offer: Offer = Offer.newBuilder()
      .setId(OfferID.newBuilder().setValue("thisisnotandid"))
      .setFrameworkId(FrameworkID.newBuilder().setValue("notanidframework"))
      .setSlaveId(SlaveID.newBuilder().setValue("notanidslave"))
      .setHostname("hostname")
      .addAllResources(Seq(
        resource("cpus", 100),
        resource("mem", 500000),
        resource("disk", 1000000000),
        Resource.newBuilder()
          .setName("ports")
          .setType(Value.Type.RANGES)
          .setRanges(
            Value.Ranges
              .newBuilder()
              .addRange(Value.Range.newBuilder().setBegin(10000).setEnd(20000)))
          .build()
      ))
      .build()

    val offers = SchedulerActor.ResourceOffers((1 to numberOfOffersPerCycle).map(_ => offer))

    import context.dispatcher
    periodicOffers = Some(
      context.system.scheduler.schedule(1.second, 1.seconds, scheduler, offers)
    )
  }

  override def postStop(): Unit = {
    periodicOffers.foreach(_.cancel())
    periodicOffers = None
    super.postStop()
  }

  override def receive: Receive = LoggingReceive {
    case driver: SchedulerDriver =>
      log.debug(s"pass on driver to scheduler $scheduler")
      scheduler ! driver

    case LaunchTasks(offers, tasks) =>
      log.debug(s"launch tasks $offers, $tasks")
      tasks.foreach { taskInfo: TaskInfo =>
        scheduler ! TaskStatus.newBuilder()
          .setSource(TaskStatus.Source.SOURCE_EXECUTOR)
          .setTaskId(taskInfo.getTaskId)
          .setState(TaskState.TASK_RUNNING)
          .build()
      }

    case KillTask(taskId) =>
      log.debug(s"kill tasks $taskId")
      scheduler ! TaskStatus.newBuilder()
        .setSource(TaskStatus.Source.SOURCE_EXECUTOR)
        .setTaskId(taskId)
        .setState(TaskState.TASK_KILLED)
        .build()

    case _ =>
  }
}
