package mesosphere.marathon.core.task.termination

import akka.Done
import mesosphere.marathon.core.task.Task

import scala.concurrent.Future

/**
  * A service that handles killing tasks. This will take care about extra logic for lost tasks,
  * apply a retry strategy and throttle kill requests to Mesos.
  */
trait TaskKillService {
  /**
    * Kill the given tasks and return a future that is completed when all of the tasks
    * have been reported as terminal.
    *
    * @param tasks the tasks that shall be killed.
    * @param reason the reason why the task shall be killed.
    * @return a future that is completed when all tasks are killed.
    */
  def killTasks(tasks: Iterable[Task], reason: TaskKillReason): Future[Done]

  /**
    * Kill the given task. The implementation should add the task onto
    * a queue that is processed short term and will eventually kill the task.
    *
    * @param task the task that shall be killed.
    * @param reason the reason why the task shall be killed.
    * @return a future that is completed when all tasks are killed.
    */
  def killTask(task: Task, reason: TaskKillReason): Future[Done]

  /**
    * Kill the given unknown task by ID and do not try to fetch its state
    * upfront. Only use this when it is certain that the task is unknown.
    *
    * @param taskId the id of the task that shall be killed.
    * @param reason the reason why the task shall be killed.
    */
  def killUnknownTask(taskId: Task.Id, reason: TaskKillReason): Unit
}
