package mesosphere.marathon.api.v1

import mesosphere.mesos.MesosUtils
import mesosphere.marathon.Protos
import scala.collection.mutable
import scala.collection.JavaConverters._
import org.hibernate.validator.constraints.NotEmpty
import mesosphere.marathon.state.MarathonState


/**
 * @author Tobi Knaup
 */
class ServiceDefinition extends MarathonState[Protos.ServiceDefinition] {
  @NotEmpty
  var id: String = ""
  @NotEmpty
  var cmd: String = ""
  var env: Map[String, String] = Map.empty
  var instances: Int = 0
  var cpus: Double = 1.0
  var mem: Double = 128.0
  var uris: Seq[String] = Seq()

  def toProto: Protos.ServiceDefinition = {
    val commandInfo = MesosUtils.commandInfo(this)
    val cpusResource = MesosUtils.scalarResource(ServiceDefinition.CPUS, cpus)
    val memResource = MesosUtils.scalarResource(ServiceDefinition.MEM, mem)

    Protos.ServiceDefinition.newBuilder
      .setId(id)
      .setCmd(commandInfo)
      .setInstances(instances)
      .addResources(cpusResource)
      .addResources(memResource)
      .build
  }

  def mergeFromProto(proto: Protos.ServiceDefinition) {
    val envMap = mutable.HashMap.empty[String, String]

    id = proto.getId
    cmd = proto.getCmd.getValue
    instances = proto.getInstances

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
        case ServiceDefinition.CPUS =>
          cpus = value
        case ServiceDefinition.MEM =>
          mem = value
      }
    }
  }

  def mergeFromProto(bytes: Array[Byte]) {
    val proto = Protos.ServiceDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }
}

object ServiceDefinition {
  val CPUS = "cpus"
  val MEM = "mem"
}
