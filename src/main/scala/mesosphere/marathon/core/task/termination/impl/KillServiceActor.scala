package mesosphere.marathon
package core.task.termination.impl

import java.time.Clock

import akka.Done
import akka.actor.{Actor, Cancellable, Props}
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.{InstanceChanged, UnknownInstanceTerminated}
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.Id
import mesosphere.marathon.core.task.termination.InstanceChangedPredicates.considerTerminal
import mesosphere.marathon.core.task.termination.KillConfig
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.Timestamp

import scala.async.Async.{async, await}
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
  * For each kill request, a [[KillStreamWatcher]] will be created, which
  * is supposed to watch the progress and complete a given promise when all watched
  * instances are reportedly terminal.
  *
  * For pods started via the default executor, it is sufficient to kill 1 task of the group,
  * which will cause all tasks to be killed
  *
  * See [[KillConfig]] for configuration options.
  */
private[impl] class KillServiceActor(
    driverHolder: MarathonSchedulerDriverHolder,
    instanceTracker: InstanceTracker,
    config: KillConfig,
    metrics: Metrics,
    clock: Clock) extends Actor with StrictLogging {
  import KillServiceActor._
  import context.dispatcher

  val instancesToKill: mutable.HashMap[Instance.Id, ToKill] = mutable.HashMap.empty
  val inFlight: mutable.HashMap[Instance.Id, ToKill] = mutable.HashMap.empty

  val instanceDoomedMetric = metrics.settableGauge("instances.inflight-kills")
  val instancesDoomedAttempsMetric = metrics.settableGauge("instances.inflight-kill-attempts")

  // We instantiate the materializer here so that all materialized streams end up as children of this actor
  implicit val materializer = ActorMaterializer()

  val retryTimer: RetryTimer = new RetryTimer {
    override def createTimer(): Cancellable = {
      context.system.scheduler.schedule(config.killRetryTimeout, config.killRetryTimeout, self, Retry)
    }
  }

  def initializeWithDecommissionedInstances() = {
    async {
      val toKillBasedOnGoal = await(instanceTracker.instancesBySpec())
        .allInstances
        .filter(i => i.state.goal != Goal.Running && i.isActive)

      KillInstancesAndForget(toKillBasedOnGoal)
    }.pipeTo(self)
  }

  override def preStart(): Unit = {
    initializeWithDecommissionedInstances()

    context.system.eventStream.subscribe(self, classOf[InstanceChanged])
    context.system.eventStream.subscribe(self, classOf[UnknownInstanceTerminated])
  }

  override def postStop(): Unit = {
    retryTimer.cancel()
    context.system.eventStream.unsubscribe(self)
    if (instancesToKill.nonEmpty) {
      logger.warn(s"Stopping $self, but not all tasks have been killed. Remaining: ${instancesToKill.keySet.mkString(", ")}, inFlight: ${inFlight.keySet.mkString(", ")}")
    }
  }

  override def receive: Receive = {
    case KillUnknownTaskById(taskId) =>
      killUnknownTaskById(taskId)

    case KillInstances(instances, promise) =>
      killInstances(instances, Some(promise))

    case KillInstancesAndForget(instances) =>
      killInstances(instances, None)

    case InstanceChanged(id, _, _, condition, _) if considerTerminal(condition) &&
      (inFlight.contains(id) || instancesToKill.contains(id)) =>
      handleTerminal(id)

    case InstanceChanged(id, _, _, _, instance) if instance.state.goal != Goal.Running =>
      if (instancesToKill.contains(id)) {
        logger.info(s"Ignoring goal change to ${instance.state.goal} for ${instance.state.goal} since the instance is already queued.")
      } else {
        logger.info(s"Adding ${id} to the queue since its goal changed to ${instance.state.goal}")
        killInstances(Seq(instance), maybePromise = None)
      }

    case UnknownInstanceTerminated(id, _, _) if inFlight.contains(id) || instancesToKill.contains(id) =>
      handleTerminal(id)

    case Retry =>
      retry()
  }

  def killUnknownTaskById(taskId: Task.Id): Unit = {
    logger.debug(s"Received KillUnknownTaskById($taskId)")
    if (!inFlight.contains(taskId.instanceId)) {
      instancesToKill.update(taskId.instanceId, ToKill(taskId.instanceId, Seq(taskId), maybeInstance = None, attempts = 0))
      processKills()
    }
  }

  def killInstances(instances: Seq[Instance], maybePromise: Option[Promise[Done]]): Unit = {
    val instanceIds = instances.map(_.instanceId)
    logger.debug(s"Adding instances $instanceIds to the queue")
    maybePromise.map(p => p.completeWith(KillStreamWatcher.watchForKilledInstances(instanceTracker.instanceUpdates, instances)))
    instances
      .filterNot(instance => inFlight.keySet.contains(instance.instanceId)) // Don't trigger a kill request for instances that are already being killed
      .foreach { instance =>
        // TODO(PODS): do we make sure somewhere that an instance has _at_least_ one task?
        logger.info(s"Process kill for ${instance.instanceId}:{${instance.state.condition}, ${instance.state.goal}} with tasks ${instance.tasksMap.values.map(_.taskId).toSeq}")
        val taskIds: IndexedSeq[Id] = instance.tasksMap.values.withFilter(!_.isTerminal).map(_.taskId)(collection.breakOut)
        instancesToKill.update(
          instance.instanceId,
          ToKill(instance.instanceId, taskIds, maybeInstance = Some(instance), attempts = 0)
        )
      }
    processKills()
  }

  def processKills(): Unit = {
    val killCount = config.killChunkSize - inFlight.size
    val toKillNow = instancesToKill.take(killCount)

    logger.info(s"processing ${toKillNow.size} kills for ${toKillNow.keys}")
    toKillNow.foreach {
      case (instanceId, data) => processKill(data)
    }

    if (inFlight.isEmpty) {
      retryTimer.cancel()
    } else {
      retryTimer.setup()
    }
  }

  def processKill(toKill: ToKill): Unit = {
    val instanceId = toKill.instanceId
    val taskIds = toKill.taskIdsToKill

    KillAction(toKill.instanceId, toKill.taskIdsToKill, toKill.maybeInstance) match {
      case KillAction.Noop =>
        ()

      case KillAction.IssueKillRequest =>
        driverHolder.driver.foreach { driver =>
          taskIds.map(_.mesosTaskId).foreach(driver.killTask)
        }
        val attempts = inFlight.get(toKill.instanceId).fold(1)(_.attempts + 1)
        inFlight.update(
          toKill.instanceId, ToKill(instanceId, taskIds, toKill.maybeInstance, attempts, issued = clock.now()))

      case KillAction.ExpungeFromState =>
        instanceTracker.forceExpunge(toKill.instanceId)
    }

    instancesToKill.remove(instanceId)
  }

  def handleTerminal(instanceId: Instance.Id): Unit = {
    instancesToKill.remove(instanceId)
    inFlight.remove(instanceId)
    logger.debug(s"$instanceId is terminal. (${instancesToKill.size} kills queued, ${inFlight.size} in flight)")
    processKills()
  }

  def retry(): Unit = {
    val now = clock.now()

    inFlight.foreach {
      case (instanceId, toKill) if (toKill.issued + config.killRetryTimeout) < now =>
        logger.warn(s"No kill ack received for $instanceId, retrying (${toKill.attempts} attempts so far)")
        processKill(toKill)

      case _ => // ignore
    }

    instanceDoomedMetric.setValue(inFlight.size.toLong)
    instancesDoomedAttempsMetric.setValue(inFlight.foldLeft(0L){ case (acc, (_, toKill)) => acc + toKill.attempts })
  }
}

private[termination] object KillServiceActor {

  sealed trait Request extends InternalRequest
  case class KillInstances(instances: Seq[Instance], promise: Promise[Done]) extends Request
  case class KillUnknownTaskById(taskId: Task.Id) extends Request
  case class KillInstancesAndForget(instances: Seq[Instance]) extends Request

  sealed trait InternalRequest
  case object Retry extends InternalRequest

  def props(
    driverHolder: MarathonSchedulerDriverHolder,
    instanceTracker: InstanceTracker,
    config: KillConfig,
    metrics: Metrics,
    clock: Clock): Props = Props(
    new KillServiceActor(driverHolder, instanceTracker, config, metrics, clock))

  /**
    * Metadata used to track which instances to kill and how many attempts have been made
    *
    * @param instanceId id of the instance to kill
    * @param taskIdsToKill ids of the tasks to kill
    * @param maybeInstance the instance, if available
    * @param attempts the number of kill attempts
    * @param issued the time of the last issued kill request
    */
  case class ToKill(
      instanceId: Instance.Id,
      taskIdsToKill: Seq[Task.Id],
      maybeInstance: Option[Instance],
      attempts: Int,
      issued: Timestamp = Timestamp.zero)
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
