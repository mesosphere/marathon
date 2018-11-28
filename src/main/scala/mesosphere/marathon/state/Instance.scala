package mesosphere.marathon
package state

import core.instance.{Reservation, Instance => CoreInstance}
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml.Raml
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

case class Instance(
    instanceId: CoreInstance.Id,
    agentInfo: Option[CoreInstance.AgentInfo],
    state: InstanceState,
    tasksMap: Map[Task.Id, Task],
    runSpecVersion: Timestamp,
    unreachableStrategy: UnreachableStrategy,
    reservation: Option[Reservation]) extends MarathonState[Protos.Json, Instance] {

  val isReserved: Boolean = state.condition == Condition.Reserved

  def isReservedTerminal: Boolean = isReserved

  override def mergeFromProto(message: Protos.Json): Instance = {
    Json.parse(message.getJson).as[Instance]
  }
  override def mergeFromProto(bytes: Array[Byte]): Instance = {
    mergeFromProto(Protos.Json.parseFrom(bytes))
  }
  override def toProto: Protos.Json = {
    Protos.Json.newBuilder().setJson(Json.stringify(Json.toJson(this))).build()
  }
  override def version: Timestamp = runSpecVersion

  def toCoreInstance(runSpec: RunSpec) = CoreInstance(instanceId, agentInfo, state, tasksMap, runSpec, reservation)
}

object Instance {

  def fromCoreInstance(instance: CoreInstance): Instance =
    Instance(instance.instanceId, instance.agentInfo, instance.state, instance.tasksMap, instance.runSpecVersion, instance.unreachableStrategy, instance.reservation)

  // Formats

  import CoreInstance.{agentFormat, tasksMapFormat}
  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat

  implicit val instanceJsonWrites: Writes[Instance] = {
    (
      (__ \ "instanceId").write[CoreInstance.Id] ~
      (__ \ "agentInfo").writeNullable[CoreInstance.AgentInfo] ~
      (__ \ "tasksMap").write[Map[Task.Id, Task]] ~
      (__ \ "runSpecVersion").write[Timestamp] ~
      (__ \ "state").write[InstanceState] ~
      (__ \ "unreachableStrategy").write[raml.UnreachableStrategy] ~ // TODO(karsten): Should we remove this after MARATHON-8325?
      (__ \ "reservation").writeNullable[Reservation]
    ) { (i) =>
        val unreachableStrategy = Raml.toRaml(i.unreachableStrategy)
        (i.instanceId, i.agentInfo, i.tasksMap, i.runSpecVersion, i.state, unreachableStrategy, i.reservation)
      }
  }

  implicit val instanceJsonReads: Reads[Instance] = {
    (
      (__ \ "instanceId").read[CoreInstance.Id] ~
      (__ \ "agentInfo").readNullable[CoreInstance.AgentInfo] ~
      (__ \ "tasksMap").read[Map[Task.Id, Task]] ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[InstanceState] ~
      (__ \ "unreachableStrategy").readNullable[raml.UnreachableStrategy] ~
      (__ \ "reservation").readNullable[Reservation]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, maybeUnreachableStrategy, reservation) =>
        val unreachableStrategy: UnreachableStrategy = maybeUnreachableStrategy.
          map(Raml.fromRaml(_)).getOrElse(UnreachableStrategy.default())
        new Instance(instanceId, agentInfo, state, tasksMap, runSpecVersion, unreachableStrategy, reservation)
      }
  }
}
