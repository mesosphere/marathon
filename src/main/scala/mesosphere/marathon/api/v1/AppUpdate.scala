package mesosphere.marathon.api.v1

import mesosphere.marathon.Protos.Constraint
import org.hibernate.validator.constraints.NotEmpty
import javax.validation.constraints.Pattern
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

import scala.collection.mutable

// TODO: Accept a task restart strategy as a constructor parameter here, to be
//       used in MarathonScheduler.

@JsonIgnoreProperties(ignoreUnknown = true)
class AppUpdate(
  @NotEmpty
  @Pattern(regexp = "^[A-Za-z0-9_.-]+$")
  var id: String = "",
  var cmd: Option[String],
  var instances: Option[Int],
  var cpus: Option[Double],
  var mem: Option[Double],
  var uris: Option[Seq[String]],
  var constraints: Option[Set[Constraint]]
) {

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
    app
  }

}