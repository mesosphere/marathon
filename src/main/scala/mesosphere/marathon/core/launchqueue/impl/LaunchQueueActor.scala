package mesosphere.marathon.core.launchqueue.impl

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{
  Terminated,
  Actor,
  ActorLogging,
  ActorRef,
  OneForOneStrategy,
  Props,
  SupervisorStrategy
}
import akka.event.LoggingReceive
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import mesosphere.marathon.core.launchqueue.{ LaunchQueueConfig, LaunchQueue }
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.{ AppDefinition, PathId }
import LaunchQueue.QueuedTaskInfo

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

private[launchqueue] object LaunchQueueActor {
  def props(config: LaunchQueueConfig, appActorProps: (AppDefinition, Int) => Props): Props = {
    Props(new LaunchQueueActor(config, appActorProps))
  }

  case class FullCount(appId: PathId)
}

/**
  * An actor-based implementation of the [[LaunchQueue]] interface.
  *
  * The methods of that interface are translated to messages in the [[LaunchQueueDelegate]] implementation.
  */
private[impl] class LaunchQueueActor(
    launchQueueConfig: LaunchQueueConfig,
    appActorProps: (AppDefinition, Int) => Props) extends Actor with ActorLogging {
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

  /** The timeout for asking any children of this actor. */
  implicit val askTimeout: Timeout = launchQueueConfig.launchQueueRequestTimeout().milliseconds

  override def receive: Receive = LoggingReceive {
    Seq(
      receiveHandlePurging,
      receiveTaskUpdateToSuspendedActor,
      receiveMessagesToSuspendedActor,
      receiveTaskUpdate,
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
  private[this] def receiveHandlePurging: Receive = {
    case Purge(appId) =>
      launchers.get(appId) match {
        case Some(actorRef) =>
          val deferredMessages: Vector[DeferredMessage] =
            suspendedLaunchersMessages(actorRef) :+ DeferredMessage(sender(), ConfirmPurge)
          suspendedLaunchersMessages += actorRef -> deferredMessages
          suspendedLauncherPathIds += appId
          actorRef ! AppTaskLauncherActor.Stop
        case None => sender() ! (())
      }

    case ConfirmPurge => sender() ! (())

    case Terminated(actorRef) =>
      launcherRefs.get(actorRef) match {
        case Some(pathId) =>
          launcherRefs -= actorRef
          launchers -= pathId

          suspendedLaunchersMessages.get(actorRef) match {
            case None =>
              log.warning("Got unexpected terminated for app {}: {}", pathId, actorRef)
            case Some(deferredMessages) =>
              deferredMessages.foreach(msg => self.tell(msg.message, msg.sender))

              suspendedLauncherPathIds -= pathId
              suspendedLaunchersMessages -= actorRef
          }
        case None =>
          log.warning("Don't know anything about terminated actor: {}", actorRef)
      }
  }

  private[this] def receiveTaskUpdateToSuspendedActor: Receive = {
    case taskChanged: TaskChanged if suspendedLauncherPathIds(taskChanged.appId) =>
      // Do not defer. If an AppTaskLauncherActor restarts, it retrieves a new task list.
      // If we defer this, there is a potential deadlock (resolved by timeout):
      //   * AppTaskLauncher waits for in-flight tasks
      //   * TaskOp gets processed and one of the update steps calls this here
      //   * ... blocked until timeout ...
      //   * The task launch notification (that the AppTaskLauncherActor waits for) gets sent to the actor
      sender() ! None
  }

  private[this] def receiveMessagesToSuspendedActor: Receive = {
    case msg @ Count(appId) if suspendedLauncherPathIds(appId) =>
      // Deferring this would also block List.
      sender() ! None

    case msg @ Add(app, count) if suspendedLauncherPathIds(app.id) =>
      deferMessageToSuspendedActor(msg, app.id)

    case msg @ RateLimiterActor.DelayUpdate(app, _) if suspendedLauncherPathIds(app.id) =>
      deferMessageToSuspendedActor(msg, app.id)
  }

  private[this] def deferMessageToSuspendedActor(msg: Any, appId: PathId): Unit = {
    val actorRef = launchers(appId)
    val deferredMessages: Vector[DeferredMessage] =
      suspendedLaunchersMessages(actorRef) :+ DeferredMessage(sender(), msg)
    suspendedLaunchersMessages += actorRef -> deferredMessages
  }

  private[this] def receiveTaskUpdate: Receive = {
    case taskChanged: TaskChanged =>
      import context.dispatcher
      launchers.get(taskChanged.appId) match {
        case Some(actorRef) =>
          val eventualCount: Future[QueuedTaskInfo] =
            (actorRef ? taskChanged).mapTo[QueuedTaskInfo]
          eventualCount.map(Some(_)).pipeTo(sender())
        case None => sender() ! None
      }
  }

  private[this] def receiveHandleNormalCommands: Receive = {
    case List =>
      import context.dispatcher
      val scatter = launchers
        .keys
        .map(appId => (self ? Count(appId)).mapTo[Option[QueuedTaskInfo]])
      val gather: Future[Seq[QueuedTaskInfo]] = Future.sequence(scatter).map(_.flatten.to[Seq])
      gather.pipeTo(sender())

    case Count(appId) =>
      import context.dispatcher
      launchers.get(appId) match {
        case Some(actorRef) =>
          val eventualCount: Future[QueuedTaskInfo] =
            (actorRef ? AppTaskLauncherActor.GetCount).mapTo[QueuedTaskInfo]
          eventualCount.map(Some(_)).pipeTo(sender())
        case None => sender() ! None
      }

    case Add(app, count) =>
      launchers.get(app.id) match {
        case None =>
          import context.dispatcher
          val actorRef = createAppTaskLauncher(app, count)
          val eventualCount: Future[QueuedTaskInfo] =
            (actorRef ? AppTaskLauncherActor.GetCount).mapTo[QueuedTaskInfo]
          eventualCount.map(_ => ()).pipeTo(sender())

        case Some(actorRef) =>
          import context.dispatcher
          val eventualCount: Future[QueuedTaskInfo] =
            (actorRef ? AppTaskLauncherActor.AddTasks(app, count)).mapTo[QueuedTaskInfo]
          eventualCount.map(_ => ()).pipeTo(sender())
      }

    case msg @ RateLimiterActor.DelayUpdate(app, _) =>
      launchers.get(app.id).foreach(_.forward(msg))
  }

  private[this] def createAppTaskLauncher(app: AppDefinition, initialCount: Int): ActorRef = {
    val actorRef = context.actorOf(appActorProps(app, initialCount), s"$childSerial-${app.id.safePath}")
    childSerial += 1
    launchers += app.id -> actorRef
    launcherRefs += actorRef -> app.id
    context.watch(actorRef)
    actorRef
  }

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case NonFatal(e) =>
      // We periodically check if scaling is needed, so we should recover. TODO: Speedup
      // Just restarting an AppTaskLauncherActor will potentially lead to starting too many tasks.
      Stop
    case m: Any => SupervisorStrategy.defaultDecider(m)
  }
}
