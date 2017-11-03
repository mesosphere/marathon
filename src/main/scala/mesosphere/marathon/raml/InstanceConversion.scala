package mesosphere.marathon
package raml

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

  implicit val instanceIdRamlWrites: Writes[instance.Instance.Id, raml.Instance_Id] = Writes { id =>
    Instance_Id(
      idString = id.idString
    )
  }

  implicit val instanceRamlWrites: Writes[instance.Instance, raml.Instance] = Writes { i =>
    Instance(
      instanceId = i.instanceId.toRaml,
      agentInfo = i.agentInfo.toRaml,
      tasksMap = i.tasksMap.map{ case (taskId, task) => taskId.idString -> task.toRaml },
      runSpecVersion = i.runSpecVersion.toOffsetDateTime,
      state = i.state.toRaml,
      unreachableStrategy = i.unreachableStrategy.toRaml
    )

  }

}
