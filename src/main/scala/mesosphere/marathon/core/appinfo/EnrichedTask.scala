package mesosphere.marathon
package core.appinfo

import mesosphere.marathon.core.task.{Task, Tasks}
import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.{Instance, Reservation}
import mesosphere.marathon.state.PathId

case class EnrichedTask(
    appId: PathId,
    task: Task,
    agentInfo: AgentInfo,
    healthCheckResults: Seq[Health],
    servicePorts: Seq[Int],
    reservation: Option[Reservation])

object EnrichedTask {

  /**
    * Construct all enriched tasks from instance.
    *
    * Only instances that have an agent info and and tasks are considered.
    *
    * @param instance           The instance the tasks belong to.
    * @param healthCheckResults Additional health statuses for the instance.
    * @param servicePorts       Service ports of the instance.
    * @return An iterable over all enriched tasks of the instance.
    */
  def fromInstance(instance: Instance, healthCheckResults: Seq[Health] = Nil, servicePorts: Seq[Int] = Nil): Iterable[EnrichedTask] = {
    instance match {
      case Instance(instanceId, Some(agentInfo), _, tasksMap @ NonEmpty(), _, reservation) =>
        tasksMap.values.map { task =>
          EnrichedTask(instanceId.runSpecId, task, agentInfo, healthCheckResults, servicePorts, reservation)
        }
      case _ => Seq.empty
    }
  }

  /**
    * Construct one enriched task from the instance.
    *
    * If the instance has not task or no agent info None is returned.
    *
    * @param instance           The instance to extract the task from.
    * @param healthCheckResults Additional health statuses for the task.
    * @return An enriched task or None.
    */
  def singleFromInstance(instance: Instance, healthCheckResults: Seq[Health] = Nil): Option[EnrichedTask] =
    instance match {
      case instance @ Instance(instanceId, Some(agentInfo), _, Tasks(firstTask, _*), _, reservation) =>
        Some(EnrichedTask(instanceId.runSpecId, firstTask, agentInfo, healthCheckResults, Nil, reservation))
      case _ => None
    }
}