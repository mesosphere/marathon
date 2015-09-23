package mesosphere.util

import javax.annotation.PreDestroy

import akka.actor.{ Actor, ActorRefFactory, PoisonPill, Props, Status }
import akka.pattern.pipe
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Allows the sequential execution of methods which return `scala.concurrent.Future`s.
  * The execution of a `scala.concurrent.Future` waits for the prior Future to complete (not only the
  * method returning the Future).
  *
  * {{{
  * scala> import mesosphere.util.SerializeExecution
  * scala> val serializeExecution = SerializeExecution(someActorRef, "serialize")
  * scala> def myFutureReturningFunc: Future[Int] = Future.successful(1)
  * scala> val result: Future[Int] = serializeExecution(myFutureReturningFunc)
  * }}}
  */
object SerializeExecution {
  private val log = LoggerFactory.getLogger(getClass.getName)

  def apply[T](actorRefFactory: ActorRefFactory, actorName: String): SerializeExecution = {
    new SerializeExecution(actorRefFactory, actorName)
  }
}

class SerializeExecution private (actorRefFactory: ActorRefFactory, actorName: String) {
  import SerializeExecution.log

  private[this] val serializeExecutionActorProps = Props[SerializeExecutionActor]()
  private[this] val serializeExecutionActorRef = actorRefFactory.actorOf(serializeExecutionActorProps, actorName)

  def apply[T](block: => Future[T]): Future[T] = {
    PromiseActor.askWithoutTimeout(
      actorRefFactory, serializeExecutionActorRef, SerializeExecutionActor.Execute(() => block))
  }

  @PreDestroy
  def close(): Unit = {
    log.debug(s"stopping $serializeExecutionActorRef")
    serializeExecutionActorRef ! PoisonPill
  }
}

/**
  * Accepts messages containing functions returning `scala.concurrent.Future`s.
  * It starts the execution of a function after the `scala.concurrent.Future`s generated
  * by prior functions have been completed.
  */
private[util] class SerializeExecutionActor extends Actor {

  import SerializeExecutionActor.{ log, Execute }

  private[this] var currentFuture: Future[Unit] = Future.successful(())

  override def receive: Receive = {
    case Execute(func) =>
      val replyTo = sender()
      log.debug(s"$self: Receiving Execute from $replyTo")
      import context.dispatcher
      currentFuture = currentFuture flatMap { _ =>
        log.debug(s"$self: Executing func from $replyTo")
        val nextFuture: Future[_] = try {
          func()
        }
        catch {
          case NonFatal(e) => Future.successful(Status.Failure(e))
        }

        pipe(nextFuture) to replyTo

        // Ignore all errors. Otherwise further `map` calls
        // have no effect. All non-fatal errors are already
        // returned to the sender by the above pipeTo.
        nextFuture recover { case NonFatal(_) => () } map { _ =>
          log.debug(s"$self: Finished executing func from $replyTo")
        }
      }
  }
}

private[util] object SerializeExecutionActor {
  private val log = LoggerFactory.getLogger(getClass.getName)
  case class Execute[T](func: () => Future[T])
}
