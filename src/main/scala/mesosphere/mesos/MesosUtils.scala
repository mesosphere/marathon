package mesosphere.mesos

import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Environment.Variable
import scala.collection._
import scala.collection.JavaConverters._
import mesosphere.marathon.api.v1.AppDefinition
import com.google.common.collect.Lists


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

  def commandInfo(app: AppDefinition) = {
    val uriProtos = app.uris.map(uri => {
      CommandInfo.URI.newBuilder()
        .setValue(uri)
        .build()
    })

    CommandInfo.newBuilder()
      .setValue(app.cmd)
      .setEnvironment(environment(app.env))
      .addAllUris(uriProtos.asJava)
      .build()
  }

  def resources(app: AppDefinition): java.lang.Iterable[Resource] = {
    Lists.newArrayList(
      scalarResource("cpus", app.cpus),
      scalarResource("mem", app.mem)
    )
  }

  def scalarResource(name: String, value: Double) = {
    Resource.newBuilder
      .setName(name)
      .setType(Value.Type.SCALAR)
      .setScalar(Value.Scalar.newBuilder.setValue(value))
      .build
  }

  def offerMatches(offer: Offer, requested: TaskInfoOrBuilder): Boolean = {
    val requestedMap = mutable.Map.empty[String, Double]
    for (resource <- requested.getResourcesList.asScala) {
      // TODO handle other resources
      if (resource.getType.eq(Value.Type.SCALAR)) {
        requestedMap(resource.getName) = resource.getScalar.getValue
      }
    }

    for (resource <- offer.getResourcesList.asScala) {
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