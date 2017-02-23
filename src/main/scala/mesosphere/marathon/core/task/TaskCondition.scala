package mesosphere.marathon
package core.task

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.task.state.TaskConditionMapping
import org.apache.mesos
import org.apache.mesos.Protos.TaskStatus.Reason

object TaskCondition {

  import org.apache.mesos.Protos.TaskState._
  import Condition._

  //scalastyle:off cyclomatic.complexity
  def apply(taskStatus: mesos.Protos.TaskStatus): Condition = {
    taskStatus.getState match {

      // The task description contains an error.
      case TASK_ERROR => Error

      // The task failed to finish successfully.
      case TASK_FAILED => Failed

      // The task finished successfully.
      case TASK_FINISHED => Finished

      // The task was killed by the executor.
      case TASK_KILLED => Killed

      // The task is being killed by the executor.
      case TASK_KILLING => Killing

      case TASK_RUNNING => Running

      // Initial state. Framework status updates should not use.
      case TASK_STAGING => Staging

      // The task is being launched by the executor.
      case TASK_STARTING => Starting

      // The task failed to launch because of a transient error. The
      // task's executor never started running. Unlike TASK_ERROR, the
      // task description is valid -- attempting to launch the task again
      // may be successful. This is a terminal state.
      case TASK_DROPPED => Dropped

      // GONE
      // The task was running on an agent that has been shutdown (e.g.,
      // the agent become partitioned, rebooted, and then reconnected to
      // the master; any tasks running before the reboot will transition
      // from UNREACHABLE to GONE). The task is no longer running. This is
      // a terminal state.

      // GONE_BY_OPERATOR:
      // The task was running on an agent that the master cannot contact;
      // the operator has asserted that the agent has been shutdown, but
      // this has not been directly confirmed by the master. If the
      // operator is correct, the task is not running and this is a
      // terminal state; if the operator is mistaken, the task might still
      // be running, and might return to the RUNNING state in the future.
      case TASK_GONE | TASK_GONE_BY_OPERATOR => Gone

      // The master has no knowledge of the task. This is typically
      // because either (a) the master never had knowledge of the task, or
      // (b) the master forgot about the task because it garbaged
      // collected its metadata about the task. The task may or may not
      // still be running.
      case TASK_UNKNOWN => Unknown

      // The task was running on an agent that has lost contact with the
      // master, typically due to a network failure or partition. The task
      // may or may not still be running.
      case TASK_UNREACHABLE => Unreachable

      // Ensure backwards compatibility with Mesos 1.0
      case TASK_LOST => inferStateForLost(taskStatus.getReason, taskStatus.getMessage)
    }
  }

  private[this] val MessageIndicatingUnknown = "Reconciliation: Task is unknown to the"

  private[this] def inferStateForLost(reason: Reason, message: String): Condition = {
    if (message.startsWith(MessageIndicatingUnknown) || TaskConditionMapping.Unknown(reason)) {
      Unknown
    } else if (TaskConditionMapping.Gone(reason)) {
      Gone
    } else if (TaskConditionMapping.Unreachable(reason)) {
      Unreachable
    } else {
      Dropped
    }
  }
}
