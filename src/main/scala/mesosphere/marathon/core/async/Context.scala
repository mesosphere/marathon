package mesosphere.marathon
package core.async

import java.time.{ Clock, Instant }

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.ClassTag

/**
  * Provides Thread Local Storage for asynchronous tasks when
  * the execution context is has a [[ContextPropagatingExecutionContext]], for example
  * [[ExecutionContexts.global]]
  *
  * In the future, we will mixin [[ContextPropagatingExecutionContext]] into all Scala
  * Execution Contexts _and_ a similar method for Java ThreadPools/Executors/etc.
  *
  * In addition, at some point, akka's dispatcher will also gain this functionality such
  * that it will also work with Actors.
  *
  * In general, the primary usage is from other Contexts, such as [[mesosphere.marathon.core.async.RunContext]]
  */
object Context {
  // allow access from ContextPropagatingDispatcher
  sealed trait ContextName[T]
  private[async] case object TestContext extends ContextName[Int]
  private[async] case object Run extends ContextName[RunContext.RunningState]

  private[async] val tls = new ThreadLocal[mutable.Map[ContextName[_], Any]] {
    override def initialValue(): mutable.Map[ContextName[_], Any] = mutable.Map.empty[ContextName[_], Any]
  }

  private[async] def put[T](key: ContextName[T], value: T): Unit = {
    tls.get().put(key, value)
  }

  private[async] def remove[T](key: ContextName[T]): Unit = {
    tls.get().remove(key)
  }

  private[async] def get[T](key: ContextName[T])(implicit tag: ClassTag[T]): Option[T] = {
    tls.get().get(key).collect { case t: T => t }
  }

  // public only to allow [[ContextPropagatingDispatcher]] to use it.
  def copy(): Map[ContextName[_], Any] = {
    tls.get().toMap
  }

  private[async] def set(map: Map[ContextName[_], Any]): Unit = {
    tls.set(mutable.Map.empty[ContextName[_], Any] ++ map)
  }

  /**
    * Run the given method with no Context, restoring the original context after the method returns
    */
  def clearContext[T](f: => T): T = withContext(Map.empty[ContextName[_], Any])(f)

  private[async] def withContext[T](newContext: Map[ContextName[_], Any])(f: => T): T = {
    val old = copy() // linter:ignore
    set(newContext)
    try {
      f
    } finally {
      set(old)
    }
  }

}

/**
  * Provides Context for Cancellation, Rollback and Expired
  * where anywhere within an asynchronous operation,
  * the logic may check for cancellation, pause, resume, etc and adjust the behavior accordingly.
  *
  * For example:
  * {{{
  *   import ExecutionContexts.global
  *
  *   RunContext.withContext {
  *     Future {
  *       // do some work
  *     }.map {
  *       CancelContext.state match {
  *         case Cancelled =>
  *           // do some cleanup
  *           throw new Exception("Cancelled")
  *         case Rollback =>
  *           // start a rollback operation instead.
  *         case Expired(at: Instant) =>
  *           // expired, timeout...
  *         case Running =>
  *           // keep going...
  *     }
  *
  *     RunContext.cancel(rollback = true)
  *   }
  * }}}
  *
  * In the example above, there could have been hundreds of future jumps across threads and take a lot of time,
  * the cancellation will still happen and you have to explicitly check for cancellation when you know how to handle
  * it.
  */
object RunContext {
  sealed trait RunState
  /** Proceed as usual */
  case object Running extends RunState
  /** Cancel when you can */
  case object Cancelled extends RunState
  /** Rollback when you can */
  case object Rollback extends RunState
  /** Timeout when you can */
  case class Expired(at: Instant) extends RunState

  private[async] class RunningState(val deadline: Instant, val parent: Option[RunningState] = None) {
    @volatile var state: RunState = parent.fold[RunState](Running)(_.state)
  }

  private def ctx: Option[RunningState] = Context.get(Context.Run)

  /**
    * The state of the cancellation including all parent contexts. Stops
    * at the first context that is not in the Running state.
    */
  def state()(implicit clock: Clock): RunState = {
    @tailrec def innerState(context: RunningState): RunState = {
      if (context.state == Running) {
        if (context.deadline.isBefore(Instant.now(clock))) {
          context.state = Expired(context.deadline)
          Expired(context.deadline)
        } else {
          context.parent match {
            case None =>
              context.state
            case Some(parent) =>
              innerState(parent)
          }
        }
      } else {
        context.state
      }
    }
    ctx.fold[RunState](Running)(innerState)
  }

  /**
    * Request for the computation to be cancelled or rolledback.
    */
  def cancel(rollback: Boolean): Unit = {
    ctx.foreach { c =>
      if (c.state == Running) {
        c.state = if (rollback) Rollback else Cancelled
      }
    }
  }

  /**
    * Perform the given operation within a cancellation context. The parent context is inherited.
    */
  def withContext[T](deadline: Instant = Instant.MAX)(f: => T): T = {
    val parent = ctx
    val newDeadline = {
      val parentDeadline = parent.fold(Instant.MAX)(_.deadline)
      if (deadline.isBefore(parentDeadline)) deadline else parentDeadline
    }
    val thisContext = new RunningState(deadline = newDeadline, parent = parent)
    thisContext.state = parent.fold[RunState](Running)(_.state)
    Context.put(Context.Run, thisContext)
    try {
      f
    } finally {
      thisContext.parent.fold(Context.remove(Context.Run))(Context.put(Context.Run, _))
    }
  }

  /**
    * Perform the given operation outside of any cancellation context.
    */
  def clearContext[T](f: => T): T = {
    val previousCtx = ctx
    Context.remove(Context.Run)
    try {
      f
    } finally {
      previousCtx.foreach(Context.put(Context.Run, _))
    }
  }
}
