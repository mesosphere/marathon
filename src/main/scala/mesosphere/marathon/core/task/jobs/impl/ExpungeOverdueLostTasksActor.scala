package mesosphere.marathon.core.task.jobs.impl

import akka.actor.{ Actor, ActorLogging, Cancellable, Props }
import akka.pattern.pipe
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.{ InstanceStateOp, Task }
import mesosphere.marathon.core.task.jobs.TaskJobsConfig
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.core.task.tracker.InstanceTracker.SpecInstances
import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.TaskStatus
import org.joda.time.DateTime

import scala.concurrent.duration._

class ExpungeOverdueLostTasksActor(
    clock: Clock,
    config: TaskJobsConfig,
    taskTracker: InstanceTracker,
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
    case Tick => taskTracker.instancesBySpec() pipeTo self
    case InstanceTracker.InstancesBySpec(appTasks) => filterLostGCTasks(appTasks).foreach(expungeLostGCTask)
  }

  def expungeLostGCTask(task: Task): Unit = {
    val timestamp = new DateTime(task.mesosStatus.fold(0L)(_.getTimestamp.toLong * 1000))
    log.warning(s"Task ${task.taskId} is lost since $timestamp and will be expunged.")
    val stateOp = InstanceStateOp.ForceExpunge(task.taskId.instanceId)
    stateOpProcessor.process(stateOp)
  }

  def filterLostGCTasks(instances: Map[PathId, SpecInstances]): Iterable[Task] = {
    def isTaskTimedOut(taskStatus: Option[TaskStatus]): Boolean = {
      taskStatus.fold(false) { status =>
        val age = clock.now().toDateTime.minus(status.getTimestamp.toLong * 1000).getMillis.millis
        age > config.taskLostExpungeGC
      }
    }
    instances.values.flatMap(_.instances.flatMap(instance =>
      instance.tasks.filter(task => task.isUnreachable && isTaskTimedOut(task.mesosStatus))))
  }
}

object ExpungeOverdueLostTasksActor {

  case object Tick

  def props(clock: Clock, config: TaskJobsConfig,
    taskTracker: InstanceTracker, stateOpProcessor: TaskStateOpProcessor): Props = {
    Props(new ExpungeOverdueLostTasksActor(clock, config, taskTracker, stateOpProcessor))
  }
}
