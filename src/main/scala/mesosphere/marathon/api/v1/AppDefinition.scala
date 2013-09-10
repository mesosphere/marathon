package mesosphere.marathon.api.v1

import mesosphere.mesos.TaskBuilder
import mesosphere.marathon.Protos
import scala.collection.mutable
import scala.collection.JavaConverters._
import org.hibernate.validator.constraints.NotEmpty
import mesosphere.marathon.state.MarathonState
import com.fasterxml.jackson.annotation.JsonProperty
import mesosphere.marathon.Protos.Constraint
import javax.validation.constraints.Pattern


/**
 * @author Tobi Knaup
 */
class AppDefinition extends MarathonState[Protos.ServiceDefinition] {
  @JsonProperty
  @NotEmpty
  var id: String = ""
  @NotEmpty
  @JsonProperty
  var cmd: String = ""
  var env: Map[String, String] = Map.empty
  var instances: Int = 0
  var cpus: Double = 1.0
  var mem: Double = 128.0
  @Pattern(regexp="(^//cmd$)|(^/[^/].*$)")
  var executor: String = ""

  //TODO
  var constraints: Set[(String, Int, Option[String])] = Set()

  var uris: Seq[String] = Seq()
  // Port gets assigned by Marathon
  var port: Int = 0

  def toProto: Protos.ServiceDefinition = {
    val commandInfo = TaskBuilder.commandInfo(this, None)
    val cpusResource = TaskBuilder.scalarResource(AppDefinition.CPUS, cpus)
    val memResource = TaskBuilder.scalarResource(AppDefinition.MEM, mem)
    val cons = constraints.map(x => {
      val b = Constraint.newBuilder()
        b.setField(x._1)
        b.setOperator(Constraint.Operator.valueOf(x._2))
        if (x._3.nonEmpty) b.setValue(x._3.get)
        b.build()
      }
    )

    Protos.ServiceDefinition.newBuilder
      .setId(id)
      .setCmd(commandInfo)
      .setInstances(instances)
      .setPort(port)
      .setExecutor(executor)
      .addAllConstraints(cons.asJava)
      .addResources(cpusResource)
      .addResources(memResource)
      .build
  }

  def mergeFromProto(proto: Protos.ServiceDefinition) {
    val envMap = mutable.HashMap.empty[String, String]

    id = proto.getId
    cmd = proto.getCmd.getValue
    executor = proto.getExecutor
    instances = proto.getInstances
    port = proto.getPort
    constraints = proto.getConstraintsList.asScala.map(
      x => (x.getField,
            x.getOperator.getNumber,
            if (x.getValue != null) Some(x.getValue) else None)
    ).toSet

    // Add command environment
    for (variable <- proto.getCmd.getEnvironment.getVariablesList.asScala) {
      envMap(variable.getName) = variable.getValue
    }
    env = envMap.toMap

    // Add URIs
    uris = proto.getCmd.getUrisList.asScala.map(_.getValue)

    // Add resources
    for (resource <- proto.getResourcesList.asScala) {
      val value = resource.getScalar.getValue
      resource.getName match {
        case AppDefinition.CPUS =>
          cpus = value
        case AppDefinition.MEM =>
          mem = value
      }
    }

    // Restore
  }

  def mergeFromProto(bytes: Array[Byte]) {
    val proto = Protos.ServiceDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }
}

object AppDefinition {
  val CPUS = "cpus"
  val MEM = "mem"
}
