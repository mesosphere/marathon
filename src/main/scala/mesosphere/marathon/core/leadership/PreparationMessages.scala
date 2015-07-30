package mesosphere.marathon.core.leadership

import akka.actor.ActorRef

/**
  * Messages for actors which need some preparation to be ready and
  * are created via `LeadershipModule.startWhenLeader()` with `considerPreparedOnStart = false`.
  */
object PreparationMessages {
  /**
    * Sent to actors which are created via `LeadershipModule.startWhenLeader()` with considerPreparedOnStart = false.
    *
    * Actors should respond with a `Prepared` message when they are prepared for action.
    */
  case object PrepareForStart

  /** Response to `PrepareForStart`. */
  case class Prepared(whenLeaderRef: ActorRef)
}
