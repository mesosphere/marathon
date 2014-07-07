package mesosphere.marathon.api.v2

import mesosphere.marathon.api.validation.FieldConstraints.FieldPortsArray
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.ContainerInfo
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.state.{ UpgradeStrategy, PathId, Timestamp }
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.lang.{ Integer => JInt, Double => JDouble }

// TODO: Accept a task restart strategy as a constructor parameter here, to be
//       used in MarathonScheduler.

@JsonIgnoreProperties(ignoreUnknown = true)
case class AppUpdate(

    id: Option[PathId] = None,

    cmd: Option[String] = None,

    env: Option[Map[String, String]] = None,

    instances: Option[JInt] = None,

    cpus: Option[JDouble] = None,

    mem: Option[JDouble] = None,

    uris: Option[Seq[String]] = None,

    @FieldPortsArray ports: Option[Seq[JInt]] = None,

    taskRateLimit: Option[JDouble] = None,

    constraints: Option[Set[Constraint]] = None,

    executor: Option[String] = None,

    container: Option[ContainerInfo] = None,

    healthChecks: Option[Set[HealthCheck]] = None,

    dependencies: Option[Set[PathId]] = None,

    upgradeStrategy: Option[UpgradeStrategy] = None,

    version: Option[Timestamp] = None) {

  /**
    * Returns the supplied [[AppDefinition]] after updating its members
    * with respect to this update request.
    */
  def apply(app: AppDefinition): AppDefinition = app.copy(
    app.id,
    cmd.getOrElse(app.cmd),
    env.getOrElse(app.env),
    instances.getOrElse(app.instances),
    cpus.getOrElse(app.cpus),
    mem.getOrElse(app.mem),
    executor.getOrElse(app.executor),
    constraints.getOrElse(app.constraints),
    uris.getOrElse(app.uris),
    ports.getOrElse(app.ports),
    taskRateLimit.getOrElse(app.taskRateLimit),
    container.orElse(app.container),
    healthChecks.getOrElse(app.healthChecks),
    dependencies.map(_.map(_.canonicalPath(app.id))).getOrElse(app.dependencies),
    upgradeStrategy.getOrElse(app.upgradeStrategy),
    Timestamp.now()
  )

}

object AppUpdate {

  /**
    * Creates an AppUpdate from the supplied AppDefinition
    */
  def fromAppDefinition(app: AppDefinition): AppUpdate =
    AppUpdate(
      Option(app.id),
      Option(app.cmd),
      Option(app.env),
      Option(app.instances),
      Option(app.cpus),
      Option(app.mem),
      Option(app.uris),
      Option(app.ports),
      Option(app.taskRateLimit),
      Option(app.constraints),
      Option(app.executor),
      app.container,
      Option(app.healthChecks),
      Option(app.dependencies),
      Option(app.upgradeStrategy),
      Option(app.version)
    )
}
