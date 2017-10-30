package mesosphere.marathon
package raml

import mesosphere.marathon.{ core, raml }
import core.instance
import TaskConversion._

object InstanceConversion extends HealthConversion with DefaultConversions with OfferConversion with UnreachableStrategyConversion {

  implicit val agentInfoRamlWrites: Writes[instance.Instance.AgentInfo, raml.AgentInfo] = Writes { ai =>
    AgentInfo(
      ai.host,
      ai.agentId,
      ai.region,
      ai.zone,
      ai.attributes.map(_.toRaml)
    )
  }

  implicit val instanceStateRamlWrites: Writes[instance.Instance.InstanceState, raml.InstanceState] = Writes { instanceState =>
    InstanceState(
      condition = Condition.fromString(instanceState.condition.toString)
        .getOrElse(throw new RuntimeException(s"can't convert mesos condition to RAML model: unexpected ${instanceState.condition}")),
      since = instanceState.since.toOffsetDateTime,
      activeSince = instanceState.activeSince.map(_.toOffsetDateTime),
      healthy = instanceState.healthy
    )
  }

  implicit val instanceRamlWrites: Writes[instance.Instance, raml.Instance] = Writes { i =>
    Instance(
      instanceId = i.instanceId.idString,
      agentInfo = i.agentInfo.toRaml,
      taskMap = i.tasksMap.map{ case (taskId, task) => taskId.idString -> task.toRaml },
      runSpecVersion = i.runSpecVersion.toOffsetDateTime,
      state = i.state.toRaml,
      unreachableStrategy = i.unreachableStrategy.toRaml
    )

  }

}
