package mesosphere.marathon.core.appinfo

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.health.Health
import mesosphere.marathon.state.PathId

case class EnrichedTask(
  appId: PathId,
  task: Task,
  healthCheckResults: Seq[Health],
  servicePorts: Seq[Int] = Nil)
