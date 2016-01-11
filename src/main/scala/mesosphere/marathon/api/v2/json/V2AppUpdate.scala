package mesosphere.marathon.api.v2.json

import java.lang.{ Double => JDouble, Integer => JInt }

import com.wix.accord._
import mesosphere.marathon.Protos.Constraint

import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.{ AppDefinition, Container, FetchUri, IpAddress, PathId, Timestamp, UpgradeStrategy }

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

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

    fetch: Option[Seq[FetchUri]] = None,

    storeUrls: Option[Seq[String]] = None,

    ports: Option[Seq[JInt]] = None,

    requirePorts: Option[Boolean] = None,

    backoff: Option[FiniteDuration] = None,

    backoffFactor: Option[JDouble] = None,

    maxLaunchDelay: Option[FiniteDuration] = None,

    container: Option[Container] = None,

    healthChecks: Option[Set[HealthCheck]] = None,

    dependencies: Option[Set[PathId]] = None,

    upgradeStrategy: Option[UpgradeStrategy] = None,

    labels: Option[Map[String, String]] = None,

    acceptedResourceRoles: Option[Set[String]] = None,

    version: Option[Timestamp] = None,

    ipAddress: Option[IpAddress] = None) {

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
    id = app.id,
    cmd = cmd.orElse(app.cmd),
    args = args.orElse(app.args),
    user = user.orElse(app.user),
    env = env.getOrElse(app.env),
    instances = instances.getOrElse(app.instances),
    cpus = cpus.getOrElse(app.cpus),
    mem = mem.getOrElse(app.mem),
    disk = disk.getOrElse(app.disk),
    executor = executor.getOrElse(app.executor),
    constraints = constraints.getOrElse(app.constraints),
    uris = uris.getOrElse(app.uris),
    fetch = fetch.getOrElse(app.fetch),
    storeUrls = storeUrls.getOrElse(app.storeUrls),
    ports = ports.getOrElse(app.ports),
    requirePorts = requirePorts.getOrElse(app.requirePorts),
    backoff = backoff.getOrElse(app.backoff),
    backoffFactor = backoffFactor.getOrElse(app.backoffFactor),
    maxLaunchDelay = maxLaunchDelay.getOrElse(app.maxLaunchDelay),
    container = container.filterNot(_ == Container.Empty).orElse(app.container),
    healthChecks = healthChecks.getOrElse(app.healthChecks),
    dependencies = dependencies.map(_.map(_.canonicalPath(app.id))).getOrElse(app.dependencies),
    upgradeStrategy = upgradeStrategy.getOrElse(app.upgradeStrategy),
    labels = labels.getOrElse(app.labels),
    acceptedResourceRoles = acceptedResourceRoles.orElse(app.acceptedResourceRoles),
    version = version.getOrElse(app.version),
    ipAddress = ipAddress.orElse(app.ipAddress)
  )

  def withCanonizedIds(base: PathId = PathId.empty): V2AppUpdate = copy(
    id = id.map(_.canonicalPath(base)),
    dependencies = dependencies.map(_.map(_.canonicalPath(base)))
  )
}

object V2AppUpdate {
  import com.wix.accord.dsl._
  import mesosphere.marathon.api.v2.Validation._

  private def updateContainsEitherUrisOrFetch: Validator[V2AppUpdate] = {
    new Validator[V2AppUpdate] {
      def apply(app: V2AppUpdate): Result = {

        val fetch = app.fetch.getOrElse(AppDefinition.DefaultFetch)
        val uris = app.uris.getOrElse(AppDefinition.DefaultUris)

        if (fetch.nonEmpty && uris.nonEmpty) {
          Failure(Set(RuleViolation(app, "AppUpdate must either contain a fetch sequence or a uri sequence", None)))
        }
        else {
          Success
        }
      }
    }
  }

  implicit val appUpdateValidator = validator[V2AppUpdate] { appUp =>
    appUp.id is valid
    appUp.dependencies is valid
    appUp.upgradeStrategy is valid
    appUp.storeUrls is optional(every(urlCanBeResolvedValidator))
    appUp.ports is optional(elementsAreUnique(V2AppDefinition.filterOutRandomPorts))
    appUp.fetch is optional(every(fetchUriHasSupportedProtocol))
    appUp is updateContainsEitherUrisOrFetch
  }
}
