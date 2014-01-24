package mesosphere.marathon.api.v2

import mesosphere.marathon.ContainerInfo
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.Protos.Constraint
import org.hibernate.validator.constraints.NotEmpty
import javax.validation.constraints.Pattern
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

import scala.collection.mutable

// TODO: Accept a task restart strategy as a constructor parameter here, to be
//       used in MarathonScheduler.

@JsonIgnoreProperties(ignoreUnknown = true)
class AppUpdate {

  var cmd: Option[String] = None

  @JsonDeserialize(contentAs = classOf[java.lang.Integer])
  var instances: Option[Int] = None

  @JsonDeserialize(contentAs = classOf[java.lang.Double])
  var cpus: Option[Double] = None

  @JsonDeserialize(contentAs = classOf[java.lang.Double])
  var mem: Option[Double] = None

  var uris: Option[Seq[String]] = None

  var constraints: Option[Set[Constraint]] = None

  @JsonDeserialize(contentAs = classOf[ContainerInfo])
  var container: Option[ContainerInfo] = None

  /**
   * Returns the supplied [[AppDefinition]] after updating its members
   * with respect to this update request.
   */
  def apply(app: AppDefinition): AppDefinition = {
    cmd.foreach { app.cmd = _ }
    instances.foreach { app.instances = _ }
    cpus.foreach { app.cpus = _ }
    mem.foreach { app.mem = _ }
    constraints.foreach { app.constraints = _ }
    container.foreach { containerInfo => app.container = Some(containerInfo) }
    app
  }

}