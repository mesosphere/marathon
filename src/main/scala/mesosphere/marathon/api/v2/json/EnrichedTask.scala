package mesosphere.marathon.api.v2.json

import mesosphere.marathon.Protos.MarathonTask

case class EnrichedTask(appId: String, task: MarathonTask)
