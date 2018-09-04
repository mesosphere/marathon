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

object EnrichedTask {
  def apply(instance: Instance, task: Task, healthCheckResults: Seq[Health],
    servicePorts: Seq[Int] = Nil): EnrichedTask = {
    new EnrichedTask(instance.runSpecId, task, instance.agentInfo, healthCheckResults, servicePorts = servicePorts,
      reservation = instance.reservation)
  }
}