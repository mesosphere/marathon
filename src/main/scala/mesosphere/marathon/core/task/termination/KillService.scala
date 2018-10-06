package mesosphere.marathon
package core.task.termination

import akka.Done
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task

import scala.concurrent.Future

/**
  * A service that handles killing tasks. This will take care about extra logic for lost tasks,
  * apply a retry strategy and throttle kill requests to Mesos.
  */
trait KillService {
  /**
    * Kill the given instances and return a future that is completed when all of the tasks
    * have been reported as terminal.
    *
    * @param instances the tasks that shall be killed.
    * @param reason the reason why the task shall be killed.
    * @return a future that is completed when all tasks are killed.
    */
  def killInstances(instances: Seq[Instance], reason: KillReason): Future[Done]

  /**
    * Kill the given instance. The implementation should add the task onto
    * a queue that is processed short term and will eventually kill the task.
    *
    * @param instance the task that shall be killed.
    * @param reason the reason why the task shall be killed.
    * @return a future that is completed when all tasks are killed.
    */
  def killInstance(instance: Instance, reason: KillReason): Future[Done]

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

  /**
    * Begins watching immediately for terminated instances. Future is completed when all instances are seen.
    */
  def watchForKilledInstances(instances: Seq[Instance]): Future[Done]
}
