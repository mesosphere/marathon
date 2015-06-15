package mesosphere.util

import akka.actor._
import scala.concurrent.Promise
import scala.concurrent.Future

/**
  * This actor waits for a response, similar to the ask pattern,
  * but without a timeout. This is needed for upgrade tasks where the
  * execution time can not be predicted, e.g. long running upgrade tasks.
  *
  * @param promise the promise that is fulfilled when this actor receives its first
  *                 and only message
  */
class PromiseActor(promise: Promise[Any]) extends Actor {
  def receive: Receive = {
    case x: Any =>
      x match {
        case Status.Failure(t) => promise.failure(t)
        case Status.Success(x) => promise.success(x)
        case _                 => promise.success(x)
      }
      context.stop(self)
  }
}

object PromiseActor {
  /**
    * Sends the given message to the given actorRef and waits indefinitely for the response. The response
    * must be of the given type T or a `akka.actor.Status.Failure`.
    *
    * @param actorRefFactory the factory for creating the internally used actor
    * @param actorRef references the actor to send the message to
    * @param message the message to be send to the actor
    */
  def askWithoutTimeout[T](actorRefFactory: ActorRefFactory, actorRef: ActorRef, message: Any): Future[T] = {
    val promise = Promise[T]()
    val promiseActor = actorRefFactory.actorOf(Props(classOf[PromiseActor], promise))
    actorRef.tell(message, promiseActor)
    promise.future
  }
}
