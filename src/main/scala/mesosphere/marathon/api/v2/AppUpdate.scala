package mesosphere.marathon.api.v2

import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.ContainerInfo
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.state.Timestamp
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import scala.concurrent.duration.FiniteDuration
import java.lang.{ Integer => JInt, Double => JDouble }

// TODO: Accept a task restart strategy as a constructor parameter here, to be
//       used in MarathonScheduler.

@JsonIgnoreProperties(ignoreUnknown = true)
case class AppUpdate(

    cmd: Option[String] = None,

    user: Option[String] = None,

    env: Option[Map[String, String]] = None,

    instances: Option[JInt] = None,

    cpus: Option[JDouble] = None,

    mem: Option[JDouble] = None,

    disk: Option[JDouble] = None,

    executor: Option[String] = None,

    constraints: Option[Set[Constraint]] = None,

    uris: Option[Seq[String]] = None,

    @FieldPortsArray ports: Option[Seq[JInt]] = None,

    @FieldJsonProperty("backoffSeconds") backoff: Option[FiniteDuration] = None,

    backoffFactor: Option[JDouble] = None,

    container: Option[ContainerInfo] = None,

    healthChecks: Option[Set[HealthCheck]] = None,

    version: Option[Timestamp] = None) {

  /**
    * Returns the supplied [[AppDefinition]] after updating its members
    * with respect to this update request.
    */
  def apply(app: AppDefinition): AppDefinition = app.copy(
    app.id,
    cmd.getOrElse(app.cmd),
    user.orElse(app.user),
    env.getOrElse(app.env),
    instances.getOrElse(app.instances),
    cpus.getOrElse(app.cpus),
    mem.getOrElse(app.mem),
    disk.getOrElse(app.disk),
    executor.getOrElse(app.executor),
    constraints.getOrElse(app.constraints),
    uris.getOrElse(app.uris),
    ports.getOrElse(app.ports),
    backoff.getOrElse(app.backoff),
    backoffFactor.getOrElse(app.backoffFactor),
    container.orElse(app.container),
    healthChecks.getOrElse(app.healthChecks),
    version.getOrElse(Timestamp.now())
  )

}

object AppUpdate {

  /**
    * Creates an AppUpdate from the supplied AppDefinition
    */
  def fromAppDefinition(app: AppDefinition): AppUpdate =
    AppUpdate(
      cmd = Option(app.cmd),
      user = app.user,
      env = Option(app.env),
      instances = Option(app.instances),
      cpus = Option(app.cpus),
      mem = Option(app.mem),
      disk = Option(app.disk),
      executor = Option(app.executor),
      constraints = Option(app.constraints),
      uris = Option(app.uris),
      ports = Option(app.ports),
      backoff = Option(app.backoff),
      backoffFactor = Option(app.backoffFactor),
      container = app.container,
      healthChecks = Option(app.healthChecks)
    )

}
