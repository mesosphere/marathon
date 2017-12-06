package mesosphere.marathon
package test

import akka.actor.{ Cancellable, Scheduler }
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * Simulates a scheduler using a SettableClock
  *
  * Does not use a separate thread for scheduling; however, scheduled tasks are executed according to their provided
  * ExecutionContext. If using a same-thread execution context, then the task will be executed by the thread that
  * advances the clock.
  *
  * If scheduler checks if tasks need to be run each time the clock is advanced. If a repeating task is scheduled to
  * happen every 10 seconds, and the clock is advanced 1 minute, then the task will be fired off once and the next run
  * be scheduled for 10 seconds later. This mirrors the behavior of the Akka scheduler when it cannot fire tasks off as
  * quickly as it is asked to.
  */
class SimulatedScheduler(clock: SettableClock) extends Scheduler {
  override def maxFrequency = 0.0
  private[this] val nextId = new AtomicLong
  private[this] val scheduledTasks = scala.collection.mutable.Map.empty[Long, ScheduledTask]
  private case class ScheduledTask(action: () => Unit, var time: Long)
  private class ScheduledTaskCancellable(id: Long) extends Cancellable {
    override def cancel() = {
      doCancel(id)
      true
    }
    override def isCancelled = scheduledTasks.contains(id)
  }

  clock.onChange { () => poll() }

  private[this] def doCancel(id: Long) = synchronized { scheduledTasks -= id }
  private[this] def poll(): Unit = synchronized {
    val now = clock.instant.toEpochMilli
    scheduledTasks.values.foreach { task =>
      if (task.time <= now) task.action()
    }
  }

  override def scheduleOnce(
    delay: FiniteDuration,
    runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = synchronized {
    val id = nextId.getAndIncrement
    val cancellable = new ScheduledTaskCancellable(id)
    scheduledTasks(id) = ScheduledTask(
      time = clock.instant.toEpochMilli + delay.toMillis,
      action = () => {
        cancellable.cancel()
        executor.execute(runnable)
      }
    )
    poll()
    cancellable
  }

  def schedule(
    initialDelay: FiniteDuration,
    interval: FiniteDuration,
    runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = synchronized {
    val id = nextId.getAndIncrement
    val cancellable = new ScheduledTaskCancellable(id)
    scheduledTasks(id) = ScheduledTask(
      time = clock.instant.toEpochMilli + initialDelay.toMillis,
      action = () => {
        scheduledTasks(id).time = clock.instant.toEpochMilli + interval.toMillis
        executor.execute(runnable)
      }
    )
    poll()
    cancellable
  }

  def taskCount = synchronized { scheduledTasks.size }
}
