package mesosphere.marathon.api.v1

import com.fasterxml.jackson.annotation.JsonProperty
import mesosphere.mesos.MesosUtils
import mesosphere.marathon.Protos
import scala.collection.mutable
import scala.collection.JavaConversions._
import org.hibernate.validator.constraints.NotEmpty


/**
 * @author Tobi Knaup
 */
class ServiceDefinition {
  @NotEmpty
  @JsonProperty
  var id: String = ""
  @NotEmpty
  @JsonProperty
  var cmd: String = ""
  @JsonProperty
  var env: Map[String, String] = Map.empty
  @JsonProperty
  var instances: Int = 0
  @JsonProperty
  var cpus: Int = 1
  @JsonProperty
  var mem: Int = 128

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

  def toProtoByteArray: Array[Byte] = {
    toProto.toByteArray
  }
}

object ServiceDefinition {

  val CPUS = "cpus"
  val MEM = "mem"

  def apply(proto: Protos.ServiceDefinition): ServiceDefinition = {
    val sd = new ServiceDefinition
    val env = mutable.HashMap.empty[String, String]

    sd.id = proto.getId
    sd.cmd = proto.getCmd.getValue
    sd.instances = proto.getInstances

    // Add command environment
    for (variable <- proto.getCmd.getEnvironment.getVariablesList) {
      env(variable.getName) = variable.getValue
    }
    sd.env = env.toMap

    // Add resources
    for (resource <- proto.getResourcesList) {
      if (resource.getName.eq(CPUS)) {
        sd.cpus = resource.getScalar.getValue.toInt
      } else if (resource.getName.eq(MEM)) {
        sd.mem = resource.getScalar.getValue.toInt
      }
    }

    sd
  }

  def parseFromProto(bytes: Array[Byte]) = apply(Protos.ServiceDefinition.parseFrom(bytes))
}
