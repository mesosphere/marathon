package mesosphere.marathon.api.v2.json

import java.lang.{ Integer => JInt, Double => JDouble }

import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.state.{
  AppDefinition,
  Container,
  PathId,
  UpgradeStrategy,
  Timestamp
}
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

@JsonIgnoreProperties(ignoreUnknown = true)
case class V2AppUpdate(

    id: Option[PathId] = None,

    cmd: Option[String] = None,

    args: Option[Seq[String]] = None,

    user: Option[String] = None,

    env: Option[Map[String, String]] = None,

    instances: Option[JInt] = None,

    cpus: Option[JDouble] = None,

    mem: Option[JDouble] = None,

    disk: Option[JDouble] = None,

    executor: Option[String] = None,

    constraints: Option[Set[Constraint]] = None,

    uris: Option[Seq[String]] = None,

    storeUrls: Option[Seq[String]] = None,

    @FieldPortsArray ports: Option[Seq[JInt]] = None,

    requirePorts: Option[Boolean] = None,

    @FieldJsonProperty("backoffSeconds") backoff: Option[FiniteDuration] = None,

    backoffFactor: Option[JDouble] = None,

    @FieldJsonProperty("maxLaunchDelaySeconds") maxLaunchDelay: Option[FiniteDuration] = None,

    container: Option[Container] = None,

    healthChecks: Option[Set[HealthCheck]] = None,

    dependencies: Option[Set[PathId]] = None,

    upgradeStrategy: Option[UpgradeStrategy] = None,

    labels: Option[Map[String, String]] = None,

    acceptedResourceRoles: Option[Set[String]] = None,

    version: Option[Timestamp] = None) {

  require(version.isEmpty || onlyVersionOrIdSet, "The 'version' field may only be combined with the 'id' field.")

  protected[api] def onlyVersionOrIdSet: Boolean = productIterator forall {
    case x @ Some(_) => x == version || x == id
    case _           => true
  }

  /**
    * Returns the supplied [[mesosphere.marathon.state.AppDefinition]] after
    * updating its members with respect to this update request.
    */
  def apply(app: AppDefinition): AppDefinition =
    apply(V2AppDefinition(app)).toAppDefinition

  /**
    * Returns the supplied [[mesosphere.marathon.api.v2.json.V2AppDefinition]]
    * after updating its members with respect to this update request.
    */
  def apply(app: V2AppDefinition): V2AppDefinition = app.copy(
    app.id,
    cmd.orElse(app.cmd),
    args.orElse(app.args),
    user.orElse(app.user),
    env.getOrElse(app.env),
    instances.getOrElse(app.instances),
    cpus.getOrElse(app.cpus),
    mem.getOrElse(app.mem),
    disk.getOrElse(app.disk),
    executor.getOrElse(app.executor),
    constraints.getOrElse(app.constraints),
    uris.getOrElse(app.uris),
    storeUrls.getOrElse(app.storeUrls),
    ports.getOrElse(app.ports),
    requirePorts.getOrElse(app.requirePorts),
    backoff.getOrElse(app.backoff),
    backoffFactor.getOrElse(app.backoffFactor),
    maxLaunchDelay.getOrElse(app.maxLaunchDelay),
    container.filterNot(_ == Container.Empty).orElse(app.container),
    healthChecks.getOrElse(app.healthChecks),
    dependencies.map(_.map(_.canonicalPath(app.id))).getOrElse(app.dependencies),
    upgradeStrategy.getOrElse(app.upgradeStrategy),
    labels.getOrElse(app.labels),
    acceptedResourceRoles.orElse(app.acceptedResourceRoles),
    version.getOrElse(Timestamp.now())
  )

  def withCanonizedIds(base: PathId = PathId.empty): V2AppUpdate = copy(
    id = id.map(_.canonicalPath(base)),
    dependencies = dependencies.map(_.map(_.canonicalPath(base)))
  )
}
