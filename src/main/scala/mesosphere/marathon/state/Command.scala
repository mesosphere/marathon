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

  def toProtoWithEnvironment(
    task: Protos.MarathonTask): MesosProtos.CommandInfo = {

    val variables = mutable.Buffer[MesosProtos.Environment.Variable]()

    // the task ID is exposed as $TASK_ID
    variables +=
      MesosProtos.Environment.Variable.newBuilder
      .setName("TASK_ID")
      .setValue(task.getId)
      .build

    // the task host is exposed as $HOST
    if (task.hasHost) variables +=
      MesosProtos.Environment.Variable.newBuilder
      .setName("HOST")
      .setValue(task.getHost)
      .build

    val ports = task.getPortsList.asScala

    // the first port (if any) is exposed as $PORT
    ports.headOption.foreach { firstPort =>
      variables +=
        MesosProtos.Environment.Variable.newBuilder
        .setName(s"PORT")
        .setValue(firstPort.toString)
        .build
    }

    // task ports are exposed as $PORT0 through $PORT{ n - 1 }
    ports.zipWithIndex.foreach {
      case (port, i) =>
        variables +=
          MesosProtos.Environment.Variable.newBuilder
          .setName(s"PORT$i")
          .setValue(port.toString)
          .build
    }

    val env = MesosProtos.Environment.newBuilder
      .addAllVariables(variables.asJava)
      .build

    this.toProto.toBuilder
      .setEnvironment(env)
      .build
  }

  def mergeFromProto(proto: MesosProtos.CommandInfo): Command =
    Command(value = proto.getValue)

  def mergeFromProto(bytes: Array[Byte]): Command =
    mergeFromProto(MesosProtos.CommandInfo.parseFrom(bytes))

}
