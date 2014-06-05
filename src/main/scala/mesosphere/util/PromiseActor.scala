package mesosphere.util

import akka.actor.Actor
import scala.concurrent.Promise
import akka.actor.Status

/**
 * This actor waits for a response, similar to the ask pattern,
 * but without a timeout. This is needed for upgrade tasks where the
 * execution time can not be predicted, e.g. long running upgrade tasks.
 *
 * @param promise
 */
class PromiseActor(promise: Promise[Any]) extends Actor {
  def receive = {
    case x =>
      x match {
        case Status.Failure(t) => promise.failure(t)
        case Status.Success(x) => promise.success(x)
        case _ => promise.success(x)
      }
      context.stop(self)
  }
}
