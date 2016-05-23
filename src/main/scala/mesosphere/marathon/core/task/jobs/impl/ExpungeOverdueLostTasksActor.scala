package mesosphere.marathon.core.task.jobs.impl

import akka.actor.{ ActorLogging, Props, Cancellable, Actor }
import akka.pattern.pipe
import com.google.inject.Provider
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.bus.MesosTaskStatus.TemporarilyUnreachable
import mesosphere.marathon.core.task.jobs.TaskJobsConfig
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.TaskTracker.AppTasks
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.TaskState
import org.apache.mesos.{ Protos => mesos }
import org.joda.time.DateTime
import scala.concurrent.duration._

class ExpungeOverdueLostTasksActor(
    clock: Clock,
    config: TaskJobsConfig,
    taskTracker: TaskTracker,
    updateProcessorProvider: Provider[TaskStatusUpdateProcessor]) extends Actor with ActorLogging {

  import ExpungeOverdueLostTasksActor._
  implicit val ec = context.dispatcher

  var tickTimer: Option[Cancellable] = None
  lazy val updateProcessor = updateProcessorProvider.get()

  override def preStart(): Unit = {
    log.info("ExpungeOverdueLostTasksActor has started")
    tickTimer = Some(context.system.scheduler.schedule(config.taskLostExpungeInitialDelay,
      config.taskLostExpungeInterval, self, Tick))
  }

  override def postStop(): Unit = {
    tickTimer.foreach(_.cancel())
    log.info("ExpungeOverdueLostTasksActor has stopped")
  }

  override def receive: Receive = {
    case Tick                             => taskTracker.tasksByApp() pipeTo self
    case TaskTracker.TasksByApp(appTasks) => filterLostGCTasks(appTasks).foreach(expungeLostGCTask)
  }

  def expungeLostGCTask(task: MarathonTask): Unit = {
    val timestamp = new DateTime(task.getStatus.getTimestamp.toLong * 1000)
    log.warning(s"Task ${task.getId} is lost since $timestamp and will be expunged.")
    // To expunge the task from the repository, task tracker etc. and to also inform all listeners
    // for this change, a TaskStatusUpdate is used, since the current infrastructure relies on that.
    // TODO: update infrastructure in 1.0 and beyond will not use task status updates.
    val update = task.getStatus.toBuilder
      .setState(TaskState.TASK_ERROR)
      .setReason(mesos.TaskStatus.Reason.REASON_TASK_UNKNOWN)
      .setTimestamp((clock.now().toDateTime.getMillis / 1000).toDouble)
      .build()
    updateProcessor.publish(update, ack = false)
  }

  def filterLostGCTasks(tasks: Map[PathId, AppTasks]): Iterable[MarathonTask] = {
    def isTimedOut(task: MarathonTask): Boolean = {
      val age = clock.now().toDateTime.minus(task.getStatus.getTimestamp.toLong * 1000).getMillis.millis
      age > config.taskLostExpungeGC
    }
    tasks.values.flatMap(_.tasks.filter {
      case TemporarilyUnreachable(task) if isTimedOut(task) => true
      case _ => false
    })
  }
}

object ExpungeOverdueLostTasksActor {

  case object Tick

  def props(clock: Clock, config: TaskJobsConfig,
            taskTracker: TaskTracker, updateProcessor: Provider[TaskStatusUpdateProcessor]): Props = {
    Props(new ExpungeOverdueLostTasksActor(clock, config, taskTracker, updateProcessor))
  }
}

