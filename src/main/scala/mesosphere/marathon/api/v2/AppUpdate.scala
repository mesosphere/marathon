package mesosphere.marathon.api.v2

import mesosphere.marathon.api.validation.FieldConstraints.FieldPortsArray
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.ContainerInfo
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.state.Timestamp
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.lang.{ Integer => JInt, Double => JDouble }

// TODO: Accept a task restart strategy as a constructor parameter here, to be
//       used in MarathonScheduler.

@JsonIgnoreProperties(ignoreUnknown = true)
case class AppUpdate(

    id: Option[String] = None, //needed for updates inside a group

    cmd: Option[String] = None,

    instances: Option[JInt] = None,

    cpus: Option[JDouble] = None,

    mem: Option[JDouble] = None,

    uris: Option[Seq[String]] = None,

    @FieldPortsArray ports: Option[Seq[JInt]] = None,

    constraints: Option[Set[Constraint]] = None,

    executor: Option[String] = None,

    container: Option[ContainerInfo] = None,

    healthChecks: Option[Set[HealthCheck]] = None,

    version: Option[Timestamp] = None) {

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
    for (v <- uris) updated = updated.copy(uris = v)
    for (v <- ports) updated = updated.copy(ports = v)
    for (v <- constraints) updated = updated.copy(constraints = v)
    for (v <- executor) updated = updated.copy(executor = v)
    for (v <- healthChecks) updated = updated.copy(healthChecks = v)

    updated.copy(
      container = this.container.orElse(app.container),
      version = Timestamp.now()
    )
  }

}

object AppUpdate {

  /**
    * Creates an AppUpdate from the supplied AppDefinition
    */
  def fromAppDefinition(app: AppDefinition): AppUpdate =
    AppUpdate(
      cmd = Option(app.cmd),
      instances = Option(app.instances),
      cpus = Option(app.cpus),
      mem = Option(app.mem),
      uris = Option(app.uris),
      ports = Option(app.ports),
      constraints = Option(app.constraints),
      executor = Option(app.executor),
      container = app.container,
      healthChecks = Option(app.healthChecks)
    )

}
