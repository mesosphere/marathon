package mesosphere.marathon
package core.launchqueue.impl

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.{Done, NotUsed}
import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Props, Stash, Status, SupervisorStrategy, Terminated}
import akka.event.LoggingReceive
import akka.pattern.{ask, pipe}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation.RescheduleReserved
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.launchqueue.impl.LaunchQueueActor.{AddFinished, QueuedAdd}
import mesosphere.marathon.core.launchqueue.impl.LaunchQueueDelegate.Add
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{PathId, RunSpec}

import scala.async.Async.{async, await}
import scala.collection.mutable.Queue
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

private[launchqueue] object LaunchQueueActor {
  def props(
    config: LaunchQueueConfig,
    instanceTracker: InstanceTracker,
    groupManager: GroupManager,
    runSpecActorProps: RunSpec => Props,
    delayUpdates: Source[RateLimiter.DelayUpdate, NotUsed]): Props = {
    Props(new LaunchQueueActor(config, instanceTracker, groupManager, runSpecActorProps, delayUpdates))
  }

  case class FullCount(appId: PathId)
  private case class QueuedAdd(sender: ActorRef, add: Add)
  private case class AddFinished(queuedAdd: QueuedAdd)
}

/**
  * An actor-based implementation of the LaunchQueue interface.
  *
  * The methods of that interface are translated to messages in the [[LaunchQueueDelegate]] implementation.
  */
private[impl] class LaunchQueueActor(
    launchQueueConfig: LaunchQueueConfig,
    instanceTracker: InstanceTracker,
    groupManager: GroupManager,
    runSpecActorProps: RunSpec => Props,
    delayUpdates: Source[RateLimiter.DelayUpdate, NotUsed]
) extends Actor with Stash with StrictLogging {
  import LaunchQueueDelegate._

  /** Currently active actors by pathId. */
  var launchers = Map.empty[PathId, ActorRef]
  /** Maps actorRefs to the PathId they handle. */
  var launcherRefs = Map.empty[ActorRef, PathId]

  /** Serial ID to ensure unique names for children actors. */
  var childSerial = 0

  // See [[receiveHandlePurging]]
  /** A message with a sender for later processing. */
  case class DeferredMessage(sender: ActorRef, message: Any)
  /** PathIds for which the actors have been currently suspended because we wait for their termination. */
  var suspendedLauncherPathIds = Set.empty[PathId]
  /** ActorRefs of the actors have been currently suspended because we wait for their termination. */
  var suspendedLaunchersMessages = Map.empty[ActorRef, Vector[DeferredMessage]].withDefaultValue(Vector.empty)

  private[this] val queuedAddOperations = Queue.empty[QueuedAdd]
  private[this] var processingAddOperation = false

  /** The timeout for asking any children of this actor. */
  implicit val askTimeout: Timeout = launchQueueConfig.launchQueueRequestTimeout().milliseconds

  override def preStart(): Unit = {
    super.preStart()

    import akka.pattern.pipe
    import context.dispatcher
    instanceTracker.instancesBySpec().pipeTo(self)

    // Using an actorMaterializer that encompasses this context will cause the stream to auto-terminate when this actor does
    implicit val materializer = ActorMaterializer()(context)
    delayUpdates.runWith(
      Sink.actorRef(
        self,
        Status.Failure(new RuntimeException("The delay updates stream closed"))))
  }

  override def receive: Receive = initializing

  def initializing: Receive = {
    case instances: InstanceTracker.InstancesBySpec =>

      instances.instancesMap.collect {
        case (id, specInstances) if specInstances.instances.exists(_.isScheduled) =>
          groupManager.runSpec(id)
      }
        .flatten
        .foreach { scheduledRunSpec =>
          launchers.getOrElse(scheduledRunSpec.id, createAppTaskLauncher(scheduledRunSpec))
        }

      context.become(initialized)

      unstashAll()

    case Status.Failure(cause) =>
      // escalate this failure
      throw new IllegalStateException("while loading instances", cause)

    case _: AnyRef =>
      stash()
  }

  def initialized: Receive = LoggingReceive {
    Seq(
      receiveHandlePurging,
      receiveInstanceUpdateToSuspendedActor,
      receiveMessagesToSuspendedActor,
      receiveInstanceUpdate,
      receiveHandleNormalCommands
    ).reduce(_.orElse[Any, Unit](_))
  }

  /**
    * Handles purging of an actor.
    *
    * Terminating an actor with a PoisonPill is not instantaneous. It will still process
    * all prior messages. While waiting for the termination of the actor, we might
    * receive further messages to the actor which would potentially lead to recreating it.
    *
    * Thus, we mark the actor as suspended and save all messages which would normally
    * have been sent to this actor. When we receive confirmation of the actor's death (Terminated),
    * we will replay these messages to ourselves with the correct sender.
    */
  @SuppressWarnings(Array("all")) // async/await
  private[this] def receiveHandlePurging: Receive = {
    case Purge(runSpecId) =>
      logger.info(s"Processing purge request for $runSpecId")
      launchers.get(runSpecId) match {
        case Some(actorRef) =>
          val deferredMessages: Vector[DeferredMessage] =
            suspendedLaunchersMessages(actorRef) :+ DeferredMessage(sender(), ConfirmPurge(runSpecId))
          suspendedLaunchersMessages += actorRef -> deferredMessages
          suspendedLauncherPathIds += runSpecId
          actorRef ! TaskLauncherActor.Stop
        case None => sender() ! Done
      }

    case ConfirmPurge(runSpecId) =>
      import context.dispatcher

      async {
        logger.info("Removing scheduled instances")
        val scheduledInstances = await(instanceTracker.specInstances(runSpecId)).filter(_.isScheduled)
        val expungingScheduledInstances = Future.sequence(scheduledInstances.map { i => instanceTracker.forceExpunge(i.instanceId) })
        await(expungingScheduledInstances): @silent
        Done
      }.pipeTo(sender())

    case Terminated(actorRef) =>
      launcherRefs.get(actorRef) match {
        case Some(pathId) =>
          launcherRefs -= actorRef
          launchers -= pathId

          suspendedLaunchersMessages.get(actorRef) match {
            case None =>
              logger.warn(s"Got unexpected terminated for runSpec $pathId: $actorRef")
            case Some(deferredMessages) =>
              deferredMessages.foreach(msg => self.tell(msg.message, msg.sender))

              suspendedLauncherPathIds -= pathId
              suspendedLaunchersMessages -= actorRef
          }
        case None =>
          logger.warn(s"Don't know anything about terminated actor: $actorRef")
      }
  }

  private[this] def receiveInstanceUpdateToSuspendedActor: Receive = {
    case update: InstanceChange if suspendedLauncherPathIds(update.runSpecId) =>
      // Do not defer. If an AppTaskLauncherActor restarts, it retrieves a new task list.
      // If we defer this, there is a potential deadlock (resolved by timeout):
      //   * AppTaskLauncher waits for in-flight tasks
      //   * TaskOp gets processed and one of the update steps calls this here
      //   * ... blocked until timeout ...
      //   * The task launch notification (that the AppTaskLauncherActor waits for) gets sent to the actor
      sender() ! Done
  }

  private[this] def receiveMessagesToSuspendedActor: Receive = {
    case msg @ Add(app, count) if suspendedLauncherPathIds(app.id) =>
      deferMessageToSuspendedActor(msg, app.id)

    case msg @ RateLimiter.DelayUpdate(app, _) if suspendedLauncherPathIds(app.id) =>
      deferMessageToSuspendedActor(msg, app.id)
  }

  private[this] def deferMessageToSuspendedActor(msg: Any, appId: PathId): Unit = {
    val actorRef = launchers(appId)
    val deferredMessages: Vector[DeferredMessage] =
      suspendedLaunchersMessages(actorRef) :+ DeferredMessage(sender(), msg)
    suspendedLaunchersMessages += actorRef -> deferredMessages
  }

  private[this] def receiveInstanceUpdate: Receive = {
    case update: InstanceChange =>
      launchers.get(update.runSpecId) match {
        case Some(actorRef) => actorRef.forward(update)
        case None => sender() ! Done
      }
  }

  @SuppressWarnings(Array("all")) // async/await
  private[this] def receiveHandleNormalCommands: Receive = {
    case add @ Add(spec, count) =>
      logger.debug(s"Adding $count instances for the ${spec.configRef}")
      // we cannot process more Add requests for one runSpec in parallel because it leads to race condition.
      // See MARATHON-8320 for details. The queue handling is helping us ensure we add an instance at a time.

      if (queuedAddOperations.isEmpty && !processingAddOperation) {
        // start processing the just received operation
        processNextAdd(QueuedAdd(sender(), add))
      } else {
        queuedAddOperations += QueuedAdd(sender(), add)
      }

    case AddFinished(queuedAdd) =>
      queuedAdd.sender ! Done

      logger.info(s"Finished processing $queuedAdd and sent done to sender.")

      processingAddOperation = false

      if (queuedAddOperations.nonEmpty) {
        processNextAdd(queuedAddOperations.dequeue())
      }

    case msg @ RateLimiter.DelayUpdate(app, _) =>
      launchers.get(app.id).foreach(_.forward(msg))

    case Status.Failure(cause) =>
      // escalate this failure
      throw new IllegalStateException("after initialized", cause)
  }

  @SuppressWarnings(Array("all")) /* async/await */
  private def processNextAdd(queuedItem: QueuedAdd): Unit = {
    logger.debug(s"Processing new queue item: $queuedItem")
    import context.dispatcher
    processingAddOperation = true

    val future = async {
      val runSpec = queuedItem.add.spec
      // Trigger TaskLaunchActor creation and sync with instance tracker.
      val actorRef = launchers.getOrElse(runSpec.id, createAppTaskLauncher(runSpec))
      // we have to await because TaskLauncherActor reads instancetracker state both directly and via published state events
      // that state affects the outcome of the sync call
      await(actorRef ? TaskLauncherActor.Sync(runSpec))

      logger.debug(s"Synced with task launcher for ${runSpec.id}")

      // Reuse resident instances that are stopped.
      val existingReservedStoppedInstances = await(instanceTracker.specInstances(runSpec.id))
        .filter(i => i.isReserved && i.state.goal == Goal.Stopped) // resident to relaunch
        .take(queuedItem.add.count)
      await(Future.sequence(existingReservedStoppedInstances.map { instance => instanceTracker.process(RescheduleReserved(instance, runSpec.version)) }))

      logger.debug(s"Rescheduled existing instances for ${runSpec.id}")

      // Schedule additional resident instances or all ephemeral instances
      val instancesToSchedule = existingReservedStoppedInstances.length.until(queuedItem.add.count).map { _ => Instance.scheduled(runSpec, Instance.Id.forRunSpec(runSpec.id)) }
      if (instancesToSchedule.nonEmpty) {
        await(instanceTracker.schedule(instancesToSchedule))
      }
      logger.info(s"Scheduling (${instancesToSchedule.length}) new instances (first five: ${instancesToSchedule.take(5)} ) due to LaunchQueue.Add for ${runSpec.id}")

      AddFinished(queuedItem)
    }
    future.pipeTo(self)
  }

  private[this] def createAppTaskLauncher(app: RunSpec): ActorRef = {
    val actorRef = context.actorOf(runSpecActorProps(app), s"$childSerial-${app.id.safePath}")
    childSerial += 1
    launchers += app.id -> actorRef
    launcherRefs += actorRef -> app.id
    context.watch(actorRef)
    actorRef
  }

  override def postStop(): Unit = {
    super.postStop()

    // Answer all outstanding requests.
    queuedAddOperations.foreach { item =>
      item.sender ! Status.Failure(new IllegalStateException("LaunchQueueActor stopped"))
    }
  }

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case NonFatal(e) =>
      // We periodically check if scaling is needed, so we should eventually recover.
      // TODO: Spead up recovery, e.g. by initiating a scale check.
      // Just restarting an AppTaskLauncherActor will potentially lead to starting too many tasks.
      Stop
    case m: Any => SupervisorStrategy.defaultDecider(m)
  }
}
