package mesosphere.marathon
package core.appinfo

import mesosphere.marathon.core.task.Task
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

object EnrichedTasks {

  object All {
    def unapply(instance: Instance): Option[Iterable[EnrichedTask]] =
      instance match {
        case Instance(instanceId, Some(agentInfo), _, tasksMap, _, _, reservation) if tasksMap.nonEmpty =>
          val enrichedTasks: Iterable[EnrichedTask] = tasksMap.values.map { task =>
            EnrichedTask(instanceId.runSpecId, task, agentInfo, Nil, Nil, reservation)
          }
          Some(enrichedTasks)
        case _ => None
      }
  }

  object Single {
    def unapply(instance: Instance): Option[EnrichedTask] =
      instance match {
        case instance @ Instance(instanceId, Some(agentInfo), _, tasksMap, _, _, reservation) if tasksMap.nonEmpty =>
          val task = instance.appTask
          Some(EnrichedTask(instanceId.runSpecId, task, agentInfo, Nil, Nil, reservation))
        case _ => None
      }
  }
}