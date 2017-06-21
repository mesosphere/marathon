package mesosphere.marathon
package core.leadership.impl

import akka.actor.{ Actor, ActorRef, Props, Stash, Status, Terminated }
import akka.event.LoggingReceive
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.leadership.PreparationMessages
import mesosphere.marathon.core.leadership.impl.WhenLeaderActor.Stop

private[leadership] object LeadershipCoordinatorActor {
  def props(whenLeaderActors: Set[ActorRef]): Props = {
    Props(new LeadershipCoordinatorActor(whenLeaderActors))
  }
}

private[leadership] class LeadershipCoordinatorActor(var whenLeaderActors: Set[ActorRef])
    extends Actor with StrictLogging with Stash {

  override def preStart(): Unit = {
    whenLeaderActors.foreach(context.watch)

    super.preStart()
  }

  override def receive: Receive = suspended

  private[impl] def suspended: Receive = {
    logger.info(s"All actors suspended:\n${whenLeaderActors.map(actorRef => s"* $actorRef").mkString("\n")}")

    LoggingReceive.withLabel("suspended") {
      case Terminated(actorRef) =>
        logger.error(s"unexpected death of $actorRef")
        whenLeaderActors -= actorRef

      case PreparationMessages.PrepareForStart =>
        whenLeaderActors.foreach(_ ! PreparationMessages.PrepareForStart)
        context.become(preparingForStart(Set(sender()), whenLeaderActors))

      case WhenLeaderActor.Stop => // nothing changes
    }
  }

  private[impl] def preparingForStart(
    ackStartRefs: Set[ActorRef],
    whenLeaderActorsWithoutAck: Set[ActorRef]): Receive = {
    if (whenLeaderActorsWithoutAck.isEmpty) {
      ackStartRefs.foreach { ackStartRef =>
        ackStartRef ! PreparationMessages.Prepared(self)
      }
      active
    } else {
      LoggingReceive.withLabel("preparingForStart") {
        case PreparationMessages.PrepareForStart =>
          context.become(preparingForStart(ackStartRefs + sender(), whenLeaderActorsWithoutAck))

        case PreparationMessages.Prepared(whenLeaderRef) =>
          context.become(preparingForStart(ackStartRefs, whenLeaderActorsWithoutAck - whenLeaderRef))

        case Terminated(actorRef) =>
          logger.error(s"unexpected death of $actorRef")
          whenLeaderActors -= actorRef
          context.become(preparingForStart(ackStartRefs - actorRef, whenLeaderActorsWithoutAck - actorRef))

        case WhenLeaderActor.Stop =>
          whenLeaderActors.foreach(_ ! Stop)
          ackStartRefs.foreach { ackStartRef =>
            ackStartRef ! Status.Failure(new IllegalStateException(s"Stopped while still preparing to start ($self)"))
          }
          context.become(suspended)
      }
    }
  }

  private[impl] def active: Receive = LoggingReceive.withLabel("active") {
    logger.info(s"All actors active:\n${whenLeaderActors.map(actorRef => s"* $actorRef").mkString("\n")}")

    LoggingReceive.withLabel("active") {
      case Terminated(actorRef) =>
        logger.error(s"unexpected death of $actorRef")
        whenLeaderActors -= actorRef

      case PreparationMessages.PrepareForStart => sender() ! PreparationMessages.Prepared(self)

      case WhenLeaderActor.Stop =>
        whenLeaderActors.foreach(_ ! Stop)
        context.become(suspended)
    }
  }
}
