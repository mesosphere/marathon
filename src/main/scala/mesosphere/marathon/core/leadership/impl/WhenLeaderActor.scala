package mesosphere.marathon
package core.leadership.impl

import akka.actor.{Actor, ActorRef, PoisonPill, Props, Stash, Status, Terminated}
import akka.event.LoggingReceive
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.leadership.LeaderDeferrable
import mesosphere.marathon.core.leadership.PreparationMessages.{PrepareForStart, Prepared}
import mesosphere.marathon.core.leadership.impl.WhenLeaderActor.{Stop, Stopped}

private[leadership] object WhenLeaderActor {
  def props(childProps: Props): Props = {
    Props(new WhenLeaderActor(childProps))
  }

  case object Stop
  case object Stopped
}

/**
  * Wraps an actor which is only started when we are currently the leader.
  */
private[impl] class WhenLeaderActor(childProps: => Props) extends Actor with StrictLogging with Stash {

  private[this] var leadershipCycle = 1

  override def receive: Receive = suspended

  private[this] val suspended: Receive = LoggingReceive.withLabel("suspended") {
    case PrepareForStart =>
      val childRef = context.actorOf(childProps, leadershipCycle.toString)
      leadershipCycle += 1
      sender() ! Prepared(self)
      context.become(active(childRef))
      unstashAll()

    case Stop => sender() ! Stopped

    case unhandled: Any =>
      if (unhandled.getClass.getAnnotation(classOf[LeaderDeferrable]) != null) {
        stash()
      } else {
        logger.error(s"Unhandled message in suspend: ${unhandled.getClass}")
        sender() ! Status.Failure(new IllegalStateException(s"not currently active ($self)"))
      }
  }

  private[impl] def active(childRef: ActorRef): Receive =
    LoggingReceive.withLabel("active") {
      case PrepareForStart => sender() ! Prepared(self)
      case Stop => stop(childRef)
      case unhandled: Any => childRef.forward(unhandled)
    }

  private[impl] def dying(stopAckRef: ActorRef, childRef: ActorRef): Receive =
    LoggingReceive.withLabel("dying") {
      case Terminated(`childRef`) =>
        unstashAll()
        stopAckRef ! Stopped
        logger.debug("becoming suspended")
        context.become(suspended)

      case unhandled: Any =>
        logger.debug(s"waiting for termination, stashing $unhandled")
        stash()
    }

  private[this] def stop(childRef: ActorRef): Unit = {
    context.watch(childRef)
    childRef ! PoisonPill
    unstashAll()
    context.become(dying(sender(), childRef))
  }

}
