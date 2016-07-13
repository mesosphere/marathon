package mesosphere.marathon.core.task.jobs.impl

import akka.actor.{ Actor, ActorLogging, Cancellable, Props }
import akka.pattern.pipe
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.core.task.jobs.TaskJobsConfig
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, TaskTracker }
import mesosphere.marathon.core.task.tracker.TaskTracker.AppTasks
import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.TaskStatus
import org.joda.time.DateTime

import scala.concurrent.duration._

class ExpungeOverdueLostTasksActor(
    clock: Clock,
    config: TaskJobsConfig,
    taskTracker: TaskTracker,
    stateOpProcessor: TaskStateOpProcessor) extends Actor with ActorLogging {

  import ExpungeOverdueLostTasksActor._
  implicit val ec = context.dispatcher

  var tickTimer: Option[Cancellable] = None

  override def preStart(): Unit = {
    log.info("ExpungeOverdueLostTasksActor has started")
    tickTimer = Some(context.system.scheduler.schedule(
      config.taskLostExpungeInitialDelay,
      config.taskLostExpungeInterval, self, Tick))
  }

  override def postStop(): Unit = {
    tickTimer.foreach(_.cancel())
    log.info("ExpungeOverdueLostTasksActor has stopped")
  }

  override def receive: Receive = {
    case Tick => taskTracker.tasksByApp() pipeTo self
    case TaskTracker.TasksByApp(appTasks) => filterLostGCTasks(appTasks).foreach(expungeLostGCTask)
  }

  def expungeLostGCTask(task: Task): Unit = {
    val timestamp = new DateTime(task.mesosStatus.fold(0L)(_.getTimestamp.toLong * 1000))
    log.warning(s"Task ${task.taskId} is lost since $timestamp and will be expunged.")
    val stateOp = TaskStateOp.ForceExpunge(task.taskId)
    stateOpProcessor.process(stateOp)
  }

  def filterLostGCTasks(tasks: Map[PathId, AppTasks]): Iterable[Task] = {
    def isTimedOut(taskStatus: Option[TaskStatus]): Boolean = {
      taskStatus.fold(false) { status =>
        val age = clock.now().toDateTime.minus(status.getTimestamp.toLong * 1000).getMillis.millis
        age > config.taskLostExpungeGC
      }
    }
    tasks.values.flatMap(_.tasks.filter(task => isTimedOut(task.mesosStatus)))
  }
}

object ExpungeOverdueLostTasksActor {

  case object Tick

  def props(clock: Clock, config: TaskJobsConfig,
    taskTracker: TaskTracker, stateOpProcessor: TaskStateOpProcessor): Props = {
    Props(new ExpungeOverdueLostTasksActor(clock, config, taskTracker, stateOpProcessor))
  }
}
