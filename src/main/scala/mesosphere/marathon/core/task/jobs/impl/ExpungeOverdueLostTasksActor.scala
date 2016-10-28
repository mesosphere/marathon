package mesosphere.marathon
package core.task.jobs.impl

import akka.actor.{ Actor, ActorLogging, Cancellable, Props }
import akka.pattern.pipe
import java.util.concurrent.TimeUnit
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.jobs.TaskJobsConfig
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, TaskStateOpProcessor }
import mesosphere.marathon.core.task.tracker.InstanceTracker.SpecInstances
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus

class ExpungeOverdueLostTasksActor(
    clock: Clock,
    config: TaskJobsConfig,
    taskTracker: InstanceTracker,
    stateOpProcessor: TaskStateOpProcessor) extends Actor with ActorLogging {

  import ExpungeOverdueLostTasksActor._
  import Timestamp._
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
    case InstanceTracker.InstancesBySpec(instances) => filterLostGCTasks(instances).foreach(expungeLostGCInstance)
  }

  def expungeLostGCInstance(instance: Instance): Unit = {
    val since = instance.state.since
    log.warning(s"Instance ${instance.instanceId} is unreachable since $since and will be expunged.")
    val stateOp = InstanceUpdateOperation.ForceExpunge(instance.instanceId)
    stateOpProcessor.process(stateOp)
  }

  /**
    * @return true if created is [[mesosphere.marathon.core.task.jobs.TaskJobsConfig.taskLostExpungeGC]] older than now.
    */
  private def isExpired(created: Timestamp, now: Timestamp): Boolean = created.until(now) > config.taskLostExpungeGC

  /**
    * @return true if task has an unreachable status that is expired.
    */
  private def isExpired(status: TaskStatus, now: Timestamp): Boolean = {
    val since: Timestamp =
      if (status.hasUnreachableTime) status.getUnreachableTime
      else Timestamp(TimeUnit.MICROSECONDS.toMillis(status.getTimestamp.toLong))
    isExpired(since, now)
  }

  /**
    * @return true if task has an unreachable status that is [[mesosphere.marathon.core.task.jobs.TaskJobsConfig.taskLostExpungeGC]]
    *         millis older than now.
    */
  private def withExpiredUnreachableStatus(now: Timestamp)(task: Task): Boolean =
    task.mesosStatus.fold(false)(status => isExpired(status, now))

  /**
    * @return instances that have been unreachable for more than [[mesosphere.marathon.core.task.jobs.TaskJobsConfig.taskLostExpungeGC]] millis.
    */
  def filterLostGCTasks(instances: Map[PathId, SpecInstances]) =
    instances.values.flatMap(_.instances)
      .withFilter(_.isUnreachable)
      .withFilter(_.tasks.exists(withExpiredUnreachableStatus(clock.now())))
}

object ExpungeOverdueLostTasksActor {

  case object Tick

  def props(clock: Clock, config: TaskJobsConfig,
    taskTracker: InstanceTracker, stateOpProcessor: TaskStateOpProcessor): Props = {
    Props(new ExpungeOverdueLostTasksActor(clock, config, taskTracker, stateOpProcessor))
  }
}
