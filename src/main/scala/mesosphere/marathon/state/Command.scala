package mesosphere.marathon.state

import mesosphere.marathon.Protos
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.apache.mesos.{ Protos => MesosProtos }
import scala.collection.mutable
import scala.collection.JavaConverters._

// TODO (if supported in the future):
//   - user
//   - URIs

@JsonIgnoreProperties(ignoreUnknown = true)
case class Command(value: String)
    extends MarathonState[MesosProtos.CommandInfo, Command] {

  def toProto: MesosProtos.CommandInfo =
    MesosProtos.CommandInfo.newBuilder
      .setValue(this.value)
      .build

  def mergeFromProto(proto: MesosProtos.CommandInfo): Command =
    Command(value = proto.getValue)

  def mergeFromProto(bytes: Array[Byte]): Command =
    mergeFromProto(MesosProtos.CommandInfo.parseFrom(bytes))

}
