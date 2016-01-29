package mesosphere.mesos.simulation

import java.util.UUID

import akka.actor.{ Actor, ActorRef, Cancellable, Props }
import akka.event.LoggingReceive
import mesosphere.mesos.simulation.DriverActor._
import mesosphere.mesos.simulation.SchedulerActor.ResourceOffers
import org.apache.mesos.Protos._
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.util.Random

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
    * `acceptOffers(o: util.Collection[OfferID], ops: util.Collection[Offer.Operation], filters: Filters): Status`
    */
  case class AcceptOffers(offerIds: Seq[OfferID], ops: Seq[Offer.Operation], filters: Filters)

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

  /**
    * Corresponds to the following method in [[org.apache.mesos.MesosSchedulerDriver]]:
    *
    * `override def suppressOffers(): Status`
    */
  case object SuppressOffers

  /**
    * Corresponds to the following method in [[org.apache.mesos.MesosSchedulerDriver]]:
    *
    * `override def reviveOffers(): Status`
    */
  case object ReviveOffers

  private case class ChangeTaskStatus(taskStatus: TaskStatus, create: Boolean)
}

class DriverActor(schedulerProps: Props) extends Actor {
  private val log = LoggerFactory.getLogger(getClass)

  private[this] val numberOfOffersPerCycle: Int = 10

  // use a fixed seed to get reproducible results
  private[this] val random = {
    val seed = 1L
    log.info(s"Random seed for this test run: $seed")
    new Random(new java.util.Random(seed))
  }

  private[this] var periodicOffers: Option[Cancellable] = None
  private[this] var scheduler: ActorRef = _

  private[this] var tasks: Map[String, TaskStatus] = Map.empty.withDefault { taskId =>
    TaskStatus.newBuilder()
      .setSource(TaskStatus.Source.SOURCE_SLAVE)
      .setTaskId(TaskID.newBuilder().setValue(taskId).build())
      .setState(TaskState.TASK_LOST)
      .build()
  }

  //scalastyle:off magic.number
  private[this] def offer: Offer = {
    def resource(name: String, value: Double): Resource = {
      Resource.newBuilder()
        .setName(name)
        .setType(Value.Type.SCALAR)
        .setScalar(Value.Scalar.newBuilder().setValue(value))
        .build()
    }
    Offer.newBuilder()
      .setId(OfferID.newBuilder().setValue(UUID.randomUUID().toString))
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
  }
  private[this] def offers: ResourceOffers =
    SchedulerActor.ResourceOffers((1 to numberOfOffersPerCycle).map(_ => offer))

  //scalastyle:on
  override def preStart(): Unit = {
    super.preStart()
    scheduler = context.actorOf(schedulerProps, "scheduler")

    import context.dispatcher
    periodicOffers = Some(
      context.system.scheduler.schedule(1.second, 1.seconds)(scheduler ! offers)
    )
  }

  override def postStop(): Unit = {
    periodicOffers.foreach(_.cancel())
    periodicOffers = None
    super.postStop()
  }

  //scalastyle:off cyclomatic.complexity
  override def receive: Receive = LoggingReceive {
    case driver: SchedulerDriver =>
      log.debug(s"pass on driver to scheduler $scheduler")
      scheduler ! driver

    case LaunchTasks(offers, tasks) =>
      simulateTaskLaunch(offers, tasks)

    case AcceptOffers(offers, ops, filters) =>
      val taskInfos = extractTaskInfos(ops)
      simulateTaskLaunch(offers, taskInfos)

    case KillTask(taskId) =>
      log.debug(s"kill task $taskId")

      tasks.get(taskId.getValue) match {
        case Some(task) =>
          scheduleStatusChange(toState = TaskState.TASK_KILLED, afterDuration = 2.seconds)(taskID = taskId)
        case None =>
          scheduleStatusChange(toState = TaskState.TASK_LOST, afterDuration = 1.second)(taskID = taskId)
      }

    case SuppressOffers => ()

    case ReviveOffers =>
      scheduler ! offers

    case ChangeTaskStatus(status, create) =>
      changeTaskStatus(status, create)

    case ReconcileTask(taskStatuses) =>
      if (taskStatuses.isEmpty) {
        tasks.values.foreach(scheduler ! _)
      }
      else {
        taskStatuses.iterator.map(_.getTaskId.getValue).map(tasks).foreach(scheduler ! _)
      }
  }
  //scalastyle:on

  private[this] def extractTaskInfos(ops: Iterable[Offer.Operation]): Iterable[TaskInfo] = {
    import scala.collection.JavaConverters._
    ops.filter(_.getType == Offer.Operation.Type.LAUNCH).flatMap { op =>
      Option(op.getLaunch).map(_.getTaskInfosList.asScala).getOrElse(Seq.empty)
    }
  }

  private[this] def simulateTaskLaunch(offers: Seq[OfferID], tasksToLaunch: Iterable[TaskInfo]): Unit = {
    if (random.nextDouble() > 0.001) {
      log.debug(s"launch tasksToLaunch $offers, $tasksToLaunch")
      tasksToLaunch.map(_.getTaskId).foreach {
        scheduleStatusChange(toState = TaskState.TASK_STAGING, afterDuration = 1.second, create = true)
      }

      if (random.nextDouble() > 0.001) {
        tasksToLaunch.map(_.getTaskId).foreach {
          scheduleStatusChange(toState = TaskState.TASK_RUNNING, afterDuration = 5.seconds)
        }
      }
      else {
        tasksToLaunch.map(_.getTaskId).foreach {
          scheduleStatusChange(toState = TaskState.TASK_FAILED, afterDuration = 5.seconds)
        }
      }
    }
    else {
      log.debug("simulating lost launch")
    }
  }

  private[this] def changeTaskStatus(status: TaskStatus, create: Boolean): Unit = {
    if (create || tasks.contains(status.getTaskId.getValue)) {
      status.getState match {
        case TaskState.TASK_ERROR | TaskState.TASK_FAILED | TaskState.TASK_FINISHED | TaskState.TASK_LOST =>
          tasks -= status.getTaskId.getValue
        case _ =>
          tasks += (status.getTaskId.getValue -> status)
      }
      log.debug(s"${tasks.size} tasks")
      scheduler ! status
    }
    else {
      if (status.getState == TaskState.TASK_LOST) {
        scheduler ! status
      }
      else {
        log.debug(s"${status.getTaskId.getValue} does not exist anymore")
      }
    }
  }

  private[this] def scheduleStatusChange(
    toState: TaskState,
    afterDuration: FiniteDuration,
    create: Boolean = false)(taskID: TaskID): Unit = {

    val newStatus = TaskStatus.newBuilder()
      .setSource(TaskStatus.Source.SOURCE_EXECUTOR)
      .setTaskId(taskID)
      .setState(toState)
      .build()
    import context.dispatcher
    context.system.scheduler.scheduleOnce(afterDuration, self, ChangeTaskStatus(newStatus, create))
  }

}
