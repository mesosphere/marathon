package mesosphere.marathon.api.v2.json

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.health.Health
import mesosphere.marathon.state.PathId

case class EnrichedTask(
  appId: PathId,
  task: MarathonTask,
  healthCheckResults: Seq[Option[Health]],
  servicePorts: Seq[Int] = Nil)
