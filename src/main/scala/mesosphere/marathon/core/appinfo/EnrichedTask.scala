package mesosphere.marathon.core.appinfo

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.state.PathId

case class EnrichedTask(
  appId: PathId,
  task: Task,
  agentInfo: AgentInfo,
  healthCheckResults: Seq[Health],
  servicePorts: Seq[Int] = Nil)
