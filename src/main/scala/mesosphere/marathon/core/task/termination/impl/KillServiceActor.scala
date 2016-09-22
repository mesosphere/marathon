package mesosphere.marathon.core.task.termination.impl

import akka.Done
import akka.actor.{ Actor, ActorLogging, Cancellable, Props }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.termination.KillConfig
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskStateOpProcessor
import mesosphere.marathon.core.event.{ InstanceChanged, UnknownInstanceTerminated }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation

import scala.collection.mutable
import scala.concurrent.Promise

/**
  * An actor that handles killing instances in chunks and depending on the instance state.
  * Lost instances will simply be expunged from state, while active instances will be killed
  * via the scheduler driver. There is be a maximum number of kills in flight, and
  * the service will only issue more kills when instances are reported terminal.
  *
  * If a kill is not acknowledged with a terminal status update within a configurable
  * time window, the kill is retried a configurable number of times. If the maximum
  * number of retries is exceeded, the instance will be expunged from state similar to a
  * lost instance.
  *
  * For each kill request, a child [[InstanceKillProgressActor]] will be spawned, which
  * is supposed to watch the progress and complete a given promise when all watched
  * instances are reportedly terminal.
  *
  * For pods started via the default executor, it is sufficient to kill 1 task of the group,
  * which will cause all tasks to be killed
  *
  * See [[KillConfig]] for configuration options.
  */
// TODO(PODS): use immutable.Seq instead of Iterables
private[impl] class KillServiceActor(
    driverHolder: MarathonSchedulerDriverHolder,
    stateOpProcessor: TaskStateOpProcessor,
    config: KillConfig,
    clock: Clock) extends Actor with ActorLogging {
  import KillServiceActor._
  import context.dispatcher

  val instancesToKill: mutable.HashMap[Instance.Id, ToKill] = mutable.HashMap.empty
  val inFlight: mutable.HashMap[Instance.Id, ToKill] = mutable.HashMap.empty

  val retryTimer: RetryTimer = new RetryTimer {
    override def createTimer(): Cancellable = {
      context.system.scheduler.schedule(config.killRetryTimeout, config.killRetryTimeout, self, Retry)
    }
  }

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[InstanceChanged])
    context.system.eventStream.subscribe(self, classOf[UnknownInstanceTerminated])
  }

  override def postStop(): Unit = {
    retryTimer.cancel()
    context.system.eventStream.unsubscribe(self)
    if (instancesToKill.nonEmpty) {
      log.warning(
        "Stopping {}, but not all tasks have been killed. Remaining: {}, inFlight: {}",
        self, instancesToKill.keySet.mkString(","), inFlight.keySet.mkString(","))
    }
  }

  override def receive: Receive = {
    case KillUnknownTaskById(taskId) =>
      killUnknownTaskById(taskId)

    case KillInstances(instances, promise) =>
      killInstances(instances, promise)

    case Terminal(event) if inFlight.contains(event.id) || instancesToKill.contains(event.id) =>
      handleTerminal(event.id)

    case UnknownInstanceTerminated(id, _, _) if inFlight.contains(id) || instancesToKill.contains(id) =>
      handleTerminal(id)

    case Retry =>
      retry()
  }

  def killUnknownTaskById(taskId: Task.Id): Unit = {
    log.debug("Received KillUnknownTaskById({})", taskId)
    instancesToKill.update(taskId.instanceId, ToKill(taskId.instanceId, taskId, maybeInstance = None, attempts = 0))
    processKills()
  }

  def killInstances(instances: Iterable[Instance], promise: Promise[Done]): Unit = {
    log.debug("Adding {} instances to queue; setting up child actor to track progress", instances.size)
    setupProgressActor(instances.map(_.instanceId), promise)
    instances.foreach { instance =>
      // TODO(PODS): do we make sure somewhere that an instance has _at_least_ one task?
      instance.tasks.headOption.foreach { task =>
        instancesToKill.update(
          instance.instanceId,
          ToKill(instance.instanceId, task.taskId, maybeInstance = Some(instance), attempts = 0)
        )
      }
    }
    processKills()
  }

  def setupProgressActor(instanceIds: Iterable[Instance.Id], promise: Promise[Done]): Unit = {
    context.actorOf(InstanceKillProgressActor.props(instanceIds, promise))
  }

  def processKills(): Unit = {
    val killCount = config.killChunkSize - inFlight.size
    val toKillNow = instancesToKill.take(killCount)

    log.info("processing {} kills", toKillNow.size)
    toKillNow.foreach {
      case (taskId, data) => processKill(data)
    }

    if (inFlight.isEmpty) {
      retryTimer.cancel()
    } else {
      retryTimer.setup()
    }
  }

  def processKill(toKill: ToKill): Unit = {
    val instanceId = toKill.instanceId
    val taskId = toKill.taskIdToKill

    // TODO(PODS): align this with other Terminal/Unreachable/whatever extractors
    val taskIsLost: Boolean = toKill.maybeInstance.fold(false) { instance =>
      instance.isGone || instance.isUnknown || instance.isDropped || instance.isUnreachable
    }

    if (taskIsLost) {
      log.warning("Expunging lost {} from state because it should be killed", taskId)
      // we will eventually be notified of a taskStatusUpdate after the task has been expunged
      stateOpProcessor.process(InstanceUpdateOperation.ForceExpunge(toKill.instanceId))
    } else {
      val knownOrNot = if (toKill.maybeInstance.isDefined) "known" else "unknown"
      log.warning("Killing {} {}", knownOrNot, taskId)
      driverHolder.driver.foreach(_.killTask(taskId.mesosTaskId))
    }

    val attempts = inFlight.get(toKill.instanceId).fold(1)(_.attempts + 1)
    inFlight.update(toKill.instanceId, ToKill(instanceId, taskId, toKill.maybeInstance, attempts, issued = clock.now()))
    instancesToKill.remove(instanceId)
  }

  def handleTerminal(instanceId: Instance.Id): Unit = {
    instancesToKill.remove(instanceId)
    inFlight.remove(instanceId)
    log.debug("{} is terminal. ({} kills queued, {} in flight)", instanceId, instancesToKill.size, inFlight.size)
    processKills()
  }

  def retry(): Unit = {
    val now = clock.now()

    inFlight.foreach {
      case (instanceId, toKill) if (toKill.issued + config.killRetryTimeout) < now =>
        log.warning("No kill ack received for {}, retrying ({} attempts so far)", instanceId, toKill.attempts)
        processKill(toKill)

      case _ => // ignore
    }
  }
}

private[termination] object KillServiceActor {

  sealed trait Request extends InternalRequest
  case class KillInstances(instances: Iterable[Instance], promise: Promise[Done]) extends Request
  case class KillUnknownTaskById(taskId: Task.Id) extends Request

  sealed trait InternalRequest
  case object Retry extends InternalRequest

  /**
    * Metadata used to track which instances to kill and how many attempts have been made
    * @param instanceId id of the instance to kill
    * @param taskIdToKill id of the task to kill (for task groups one task is killed, not all)
    * @param maybeInstance the instance, if available
    * @param attempts the number of kill attempts
    * @param issued the time of the last issued kill request
    */
  case class ToKill(
    instanceId: Instance.Id,
    taskIdToKill: Task.Id,
    maybeInstance: Option[Instance],
    attempts: Int,
    issued: Timestamp = Timestamp.zero)

  def props(
    driverHolder: MarathonSchedulerDriverHolder,
    stateOpProcessor: TaskStateOpProcessor,
    config: KillConfig,
    clock: Clock): Props = Props(
    new KillServiceActor(driverHolder, stateOpProcessor, config, clock))
}

/**
  * Wraps a timer into an interface that hides internal mutable state behind simple setup and cancel methods
  */
private[this] trait RetryTimer {
  private[this] var retryTimer: Option[Cancellable] = None

  /** Creates a new timer when setup() is called */
  def createTimer(): Cancellable

  /**
    * Cancel the timer if there is one.
    */
  final def cancel(): Unit = {
    retryTimer.foreach(_.cancel())
    retryTimer = None
  }

  /**
    * Setup a timer if there is no timer setup already. Will do nothing if there is a timer.
    * Note that if the timer is scheduled only once, it will not be removed until you call cancel.
    */
  final def setup(): Unit = {
    if (retryTimer.isEmpty) {
      retryTimer = Some(createTimer())
    }
  }
}
