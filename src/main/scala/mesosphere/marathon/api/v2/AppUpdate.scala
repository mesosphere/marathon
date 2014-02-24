package mesosphere.marathon.api.v2

import mesosphere.marathon.ContainerInfo
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.FieldConstraints.FieldJsonDeserialize
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

import scala.collection.mutable

// TODO: Accept a task restart strategy as a constructor parameter here, to be
//       used in MarathonScheduler.

@JsonIgnoreProperties(ignoreUnknown = true)
case class AppUpdate(

  cmd: Option[String] = None,

  @FieldJsonDeserialize(contentAs = classOf[java.lang.Integer])
  instances: Option[Int] = None,

  @FieldJsonDeserialize(contentAs = classOf[java.lang.Double])
  cpus: Option[Double] = None,

  @FieldJsonDeserialize(contentAs = classOf[java.lang.Double])
  mem: Option[Double] = None,

  uris: Option[Seq[String]] = None,

  constraints: Option[Set[Constraint]] = None,

  executor: Option[String] = None,

  @FieldJsonDeserialize(contentAs = classOf[ContainerInfo])
  container: Option[ContainerInfo] = None

) {

  // the default constructor exists solely for interop with automatic
  // (de)serializers
  def this() = this(cmd = None)

  /**
   * Returns the supplied [[AppDefinition]] after updating its members
   * with respect to this update request.
   */
  def apply(app: AppDefinition): AppDefinition = {

    var updated = app

    for (v <- cmd) updated = updated.copy(cmd = v)
    for (v <- instances) updated = updated.copy(instances = v)
    for (v <- cpus) updated = updated.copy(cpus = v)
    for (v <- mem) updated = updated.copy(mem = v)
    for (v <- constraints) updated = updated.copy(constraints = v)
    for (v <- executor) updated = updated.copy(executor = v)
    updated.copy(container = this.container)
  }

}