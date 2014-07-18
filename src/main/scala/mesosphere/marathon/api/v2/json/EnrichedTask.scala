package mesosphere.marathon.api.v2.json

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.health.Health

case class EnrichedTask(
  appId: String,
  task: MarathonTask,
  healthCheckResults: Seq[Option[Health]])
