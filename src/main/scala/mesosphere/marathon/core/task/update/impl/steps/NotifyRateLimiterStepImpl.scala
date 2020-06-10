package mesosphere.marathon
package core.task.update.impl.steps

import akka.Done
import com.google.inject.{Inject, Provider}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceChangeHandler}
import mesosphere.marathon.core.launchqueue.LaunchQueue

import scala.concurrent.Future

class NotifyRateLimiterStepImpl @Inject() (launchQueueProvider: Provider[LaunchQueue]) extends InstanceChangeHandler with StrictLogging {

  import NotifyRateLimiterStep._

  private[this] lazy val launchQueue = launchQueueProvider.get()

  override def name: String = "notifyRateLimiter"
  override def metricName: String = "notify-rate-limiter"

  /**
    * RateLimiter is triggered for every InstanceChange event. Right now,
    * InstanceChange is very tied to condition changes so InstanceChange pretty
    * much was condition change all the time. But now, we will be having
    * InstanceChange event also for goal changes and we don't want to trigger
    * RateLimiter for that.
    */
  override def process(update: InstanceChange): Future[Done] = {

    update.instance match {
      case instance if agentDraining(instance) =>
        val runSpecId = update.runSpecId
        val instanceId = instance.instanceId
        val agentId = instance.agentInfo.flatMap(_.agentId).getOrElse("unknown")
        logger.info(
          s"Reset delay for $runSpecId because status update for $instanceId (${update.condition}) indicated $agentId is being drained"
        )
        launchQueue.resetDelay(instance.runSpec)

      case instance if limitWorthy(instance.state.condition) && update.conditionUpdated =>
        launchQueue.addDelay(instance.runSpec)

      case instance if advanceWorthy(instance.state.condition) && update.conditionUpdated =>
        launchQueue.advanceDelay(instance.runSpec)

      case _ =>
      // nothing to do
    }

    Future.successful(Done)
  }
}

private[steps] object NotifyRateLimiterStep extends StrictLogging {

  /**
    * If any of the instance's tasks has a mesos status in state TASK_KILLED or
    * TASK_GONE_BY_OPERATOR, and a reason REASON_SLAVE_DRAINING, then the agent
    * that task was running on is being drained.
    *
    * The only relevant states here are TASK_KILLED and TASK_GONE_BY_OPERATOR.
    * See the public design doc for more info:
    * https://docs.google.com/document/d/1w3O80NFE6m52XNMv7EdXSO-1NebEs8opA8VZPG1tW0Y/edit#
    *
    * @return true, if the given instance indicates that the associated agent is
    *         being drained.
    */
  def agentDraining(instance: Instance): Boolean = {
    import org.apache.mesos.{Protos => MesosProtos}
    instance.tasksMap.valuesIterator.exists { task =>
      task.status.mesosStatus.fold(false) { status =>
        val relevantState = status.hasState &&
          (status.getState == MesosProtos.TaskState.TASK_KILLED || status.getState == MesosProtos.TaskState.TASK_GONE_BY_OPERATOR)
        val isDraining = status.hasReason && status.getReason == MesosProtos.TaskStatus.Reason.REASON_SLAVE_DRAINING

        relevantState && isDraining
      }
    }
  }

  // A set of conditions that are worth rate limiting the associated runSpec
  val limitWorthy: Set[Condition] = Set(
    Condition.Dropped,
    Condition.Error,
    Condition.Failed,
    Condition.Gone,
    Condition.Finished
  )

  // A set of conditions that are worth advancing an existing delay of the corresponding runSpec
  val advanceWorthy: Set[Condition] = Set(
    Condition.Staging,
    Condition.Starting,
    Condition.Running,
    Condition.Provisioned
  )
}
