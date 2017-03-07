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
  * In general, the primary usage is from other Contexts, such as [[CancelContext]]
  */
object Context {
  private[async] sealed trait ContextName[T]
  private[async] case object TestContext extends ContextName[Int]
  case object Cancel extends ContextName[CancelContext.CancellationState]
  case object Deadline extends ContextName[DeadlineContext.DeadlineInfo]

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

  private[async] def copy: Map[ContextName[_], Any] = {
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
    val old = copy // linter:ignore
    set(newContext)
    try {
      f
    } finally {
      set(old)
    }
  }

}

/**
  * Provides Context for Cancellation, where anywhere within an asynchronous operation,
  * the logic may check for cancellation or rollback and adjust the behavior accordingly.
  *
  * For example:
  * {{{
  *   import ExecutionContexts.global
  *
  *   CancelContext.withContext {
  *     Future {
  *       // do some work
  *     }.map {
  *       CancelContext.state match {
  *         case Cancelled =>
  *           // do some cleanup
  *           throw new Exception("Cancelled")
  *         case Rollback =>
  *           // start a rollback operation instead.
  *         case Running =>
  *           // keep going...
  *     }
  *
  *     CancelContext.cancel(rollback = true)
  *   }
  * }}}
  *
  * In the example above, there could have been hundreds of future jumps across threads and take a lot of time,
  * the cancellation will still happen and you have to explicitly check for cancellation when you know how to handle
  * it.
  */
object CancelContext {
  sealed trait CancelState
  /** Proceed as usual */
  case object Running extends CancelState
  /** Cancel when you can */
  case object Cancelled extends CancelState
  /** Rollback when you can */
  case object Rollback extends CancelState

  private[async] class CancellationState(val parent: Option[CancellationState] = None) {
    @volatile var state: CancelState = parent.fold[CancelState](Running)(_.state)
  }

  private def ctx: Option[CancellationState] = Context.get(Context.Cancel)

  /**
    * The state of the cancellation including all parent contexts. Stops
    * at the first context that is not in the Running state.
    */
  def state: CancelState = {
    @tailrec def innerState(context: CancellationState): CancelState = {
      if (context.state == Running) {
        context.parent match {
          case None =>
            context.state
          case Some(parent) =>
            innerState(parent)
        }
      } else {
        context.state
      }
    }
    ctx.fold[CancelState](Running)(innerState)
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
  def withContext[T](f: => T): T = {
    val parent = ctx
    val thisContext = new CancellationState(parent = parent)
    thisContext.state = parent.fold[CancelState](Running)(_.state)
    Context.put(Context.Cancel, thisContext)
    try {
      f
    } finally {
      thisContext.parent.fold(Context.remove(Context.Cancel))(Context.put(Context.Cancel, _))
    }
  }

  /**
    * Perform the given operation outside of any cancellation context.
    */
  def clearContext[T](f: => T): T = {
    val previousCtx = ctx
    Context.remove(Context.Cancel)
    try {
      f
    } finally {
      previousCtx.foreach(Context.put(Context.Cancel, _))
    }
  }
}

/**
  * Provides Context for Deadlines, where anywhere within an asynchronous operation,
  * the logic may check if the deadline expired and adjust/timeout accordingly.
  *
  * For example:
  * {{{
  *   import ExecutionContexts.global
  *
  *   DeadlineContext.withDeadline(Instant.now().plus(Duration.ofSeconds(30)) {
  *     Future {
  *       // do some work
  *     }.map {
  *       if (DeadlineContext.isExpired) {
  *         // cleanup
  *         throw new TimeoutException
  *       } else {
  *         // keep doing work
  *       }
  *     }
  * *   }
  * }}}
  *
  *
  */
object DeadlineContext {
  private[async] case class DeadlineInfo(deadline: Instant, parent: Option[DeadlineInfo])

  /**
    * Check if the deadline has expired.
    */
  def isExpired()(implicit clock: Clock): Boolean = {
    Context.get(Context.Deadline).fold[Boolean](false)(_.deadline.isBefore(Instant.now(clock)))
  }

  /**
    * Set a deadline for the computation suggesting that it ends
    * by the given instant.  If there is a parent deadline that expires
    * sooner, that value will _always_ be preferred.
    */
  def withDeadline[T](deadline: Instant)(f: => T): T = {
    val parentCtx = Context.get(Context.Deadline)
    parentCtx.fold {
      Context.put(Context.Deadline, DeadlineInfo(deadline, None))
    } { parent =>
      if (!parent.deadline.isBefore(deadline)) {
        Context.put(Context.Deadline, DeadlineInfo(deadline, Some(parent)))
      }
    }
    try {
      f
    } finally {
      parentCtx.fold(Context.remove(Context.Deadline))(Context.put(Context.Deadline, _))
    }
  }

  /**
    * Clear the deadline for the given method, restoring it after.
    */
  def clearDeadline[T](f: => T): T = {
    val previousCtx = Context.get(Context.Deadline)
    Context.remove(Context.Deadline)
    try {
      f
    } finally {
      previousCtx.fold(Context.remove(Context.Deadline))(Context.put(Context.Deadline, _))
    }
  }
}
