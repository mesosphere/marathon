package mesosphere.marathon
package raml

trait InstanceConversion {

  /**
    * generate a instance RAML for some instance.
    */
  implicit val instance: Writes[core.instance.Instance, Instance] = Writes { src =>
    val agentInfo = src.agentInfo.map { agentInfo =>
      AgentInfo(agentInfo.host, agentInfo.agentId, agentInfo.region, agentInfo.zone, Seq.empty)
    }
    val unreachableStrategy = src.unreachableStrategy.toRaml
    val state = src.state.toRaml

    Instance(src.instanceId.idString, state, agentInfo, src.runSpecVersion.toOffsetDateTime, unreachableStrategy)
  }

  /**
    * generate a instance RAML for an instance state.
    */
  implicit val instanceStateWrites: Writes[core.instance.Instance.InstanceState, InstanceState] = Writes { src =>
    InstanceState(
      Condition.fromString(src.condition.toString).get,
      src.since.toOffsetDateTime,
      src.activeSince.map(_.toOffsetDateTime),
      src.healthy,
      Goal.fromString(src.goal.toString).get
    )
  }
}
