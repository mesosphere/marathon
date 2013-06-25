package mesosphere.mesos

import org.apache.mesos.Protos.{Value, Resource, CommandInfo, Environment}
import org.apache.mesos.Protos.Environment.Variable
import scala.collection._
import mesosphere.marathon.api.v1.ServiceDefinition


/**
 * @author Tobi Knaup
 */

object MesosUtils {

  def environment(vars: Map[String, String]) = {
    val builder = Environment.newBuilder()

    for ((key, value) <- vars) {
      val variable = Variable.newBuilder().setName(key).setValue(value)
      builder.addVariables(variable)
    }

    builder.build()
  }

  def commandInfo(service: ServiceDefinition) = {
    CommandInfo.newBuilder()
      .setValue(service.cmd)
      .setEnvironment(environment(service.env))
      .build()
  }

  def resources(service: ServiceDefinition) = {
    List(
      scalarResource("cpus", service.cpus),
      scalarResource("mem", service.mem)
    )
  }

  def scalarResource(name: String, value: Double) = {
    Resource.newBuilder
      .setName(name)
      .setType(Value.Type.SCALAR)
      .setScalar(Value.Scalar.newBuilder.setValue(value))
      .build
  }

  def offerMatches(offered: List[Resource], requested: List[Resource]): Boolean = {
    val requestedMap = mutable.Map.empty[String, Double]
    for (resource <- requested) {
      // TODO handle other resources
      if (resource.getType.eq(Value.Type.SCALAR)) {
        requestedMap(resource.getName) = resource.getScalar.getValue
      }
    }

    for (resource <- offered) {
      // TODO handle other resources
      if (resource.getType.eq(Value.Type.SCALAR)) {
        val requestedValue = requestedMap.getOrElse(resource.getName, Double.NegativeInfinity)

        if (requestedValue > resource.getScalar.getValue) {
          return false
        }
      }
    }

    true
  }

}