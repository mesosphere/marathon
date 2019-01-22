package mesosphere.marathon
package core.task.termination

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task

/**
  * A service that handles killing tasks. This will take care about extra logic for lost tasks,
  * apply a retry strategy and throttle kill requests to Mesos.
  */
trait KillService {
  /**
    * Kill the passed instances. Similarly to the [[killUnknownTask()]] method the implementation will *not* create a
    * [[mesosphere.marathon.core.task.termination.impl.KillStreamWatcher]] internally saving resources in cases when
    * the caller is not interested in the result.
    *
    * @param instances
    * @param reason
    */
  def killInstancesAndForget(instances: Seq[Instance], reason: KillReason): Unit

  /**
    * Kill the given unknown task by ID and do not try to fetch its state
    * upfront. Only use this when it is certain that the task is unknown.
    *
    * @param taskId the id of the task that shall be killed.
    * @param reason the reason why the task shall be killed.
    */
  def killUnknownTask(taskId: Task.Id, reason: KillReason): Unit
}
