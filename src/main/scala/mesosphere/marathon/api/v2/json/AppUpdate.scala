package mesosphere.marathon.api.v2.json

import com.wix.accord.dsl._
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state._

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

case class AppUpdate(

    id: Option[PathId] = None,

    cmd: Option[String] = None,

    args: Option[Seq[String]] = None,

    user: Option[String] = None,

    env: Option[Map[String, String]] = None,

    instances: Option[Int] = None,

    cpus: Option[Double] = None,

    mem: Option[Double] = None,

    disk: Option[Double] = None,

    executor: Option[String] = None,

    constraints: Option[Set[Constraint]] = None,

    fetch: Option[Seq[FetchUri]] = None,

    storeUrls: Option[Seq[String]] = None,

    portDefinitions: Option[Seq[PortDefinition]] = None,

    requirePorts: Option[Boolean] = None,

    backoff: Option[FiniteDuration] = None,

    backoffFactor: Option[Double] = None,

    maxLaunchDelay: Option[FiniteDuration] = None,

    container: Option[Container] = None,

    healthChecks: Option[Set[HealthCheck]] = None,

    readinessChecks: Option[Seq[ReadinessCheck]] = None,

    dependencies: Option[Set[PathId]] = None,

    upgradeStrategy: Option[UpgradeStrategy] = None,

    labels: Option[Map[String, String]] = None,

    acceptedResourceRoles: Option[Set[String]] = None,

    version: Option[Timestamp] = None,

    ipAddress: Option[IpAddress] = None,

    residency: Option[Residency] = None) {

  require(version.isEmpty || onlyVersionOrIdSet, "The 'version' field may only be combined with the 'id' field.")

  protected[api] def onlyVersionOrIdSet: Boolean = productIterator forall {
    case x @ Some(_) => x == version || x == id
    case _           => true
  }

  def isResident: Boolean = residency.isDefined

  def persistentVolumes: Iterable[PersistentVolume] = {
    container.fold(Seq.empty[Volume])(_.volumes).collect{ case vol: PersistentVolume => vol }
  }

  def empty(appId: PathId): AppDefinition = {
    def volumes: Iterable[Volume] = container.fold(Seq.empty[Volume])(_.volumes)
    def externalVolumes: Iterable[ExternalVolume] = volumes.collect { case vol: ExternalVolume => vol }
    val residency = if (persistentVolumes.nonEmpty) Some(Residency.defaultResidency) else None
    val upgradeStrategy = if (residency.isDefined || isResident
      || externalVolumes.nonEmpty) UpgradeStrategy.forResidentTasks
    else UpgradeStrategy.empty
    apply(AppDefinition(appId, residency = residency, upgradeStrategy = upgradeStrategy))
  }

  /**
    * Returns the supplied [[mesosphere.marathon.state.AppDefinition]]
    * after updating its members with respect to this update request.
    */
  def apply(app: AppDefinition): AppDefinition = app.copy(
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
    fetch = fetch.getOrElse(app.fetch),
    storeUrls = storeUrls.getOrElse(app.storeUrls),
    portDefinitions = portDefinitions.getOrElse(app.portDefinitions),
    requirePorts = requirePorts.getOrElse(app.requirePorts),
    backoff = backoff.getOrElse(app.backoff),
    backoffFactor = backoffFactor.getOrElse(app.backoffFactor),
    maxLaunchDelay = maxLaunchDelay.getOrElse(app.maxLaunchDelay),
    container = container.filterNot(_ == Container.Empty).orElse(app.container),
    healthChecks = healthChecks.getOrElse(app.healthChecks),
    readinessChecks = readinessChecks.getOrElse(app.readinessChecks),
    dependencies = dependencies.map(_.map(_.canonicalPath(app.id))).getOrElse(app.dependencies),
    upgradeStrategy = upgradeStrategy.getOrElse(app.upgradeStrategy),
    labels = labels.getOrElse(app.labels),
    acceptedResourceRoles = acceptedResourceRoles.orElse(app.acceptedResourceRoles),
    ipAddress = ipAddress.orElse(app.ipAddress),
    // The versionInfo may never be overridden by an AppUpdate.
    // Setting the version in AppUpdate means that the user wants to revert to that version. In that
    // case, we do not update the current AppDefinition but revert completely to the specified version.
    // For all other updates, the GroupVersioningUtil will determine a new version if the AppDefinition
    // has really changed.
    versionInfo = app.versionInfo,
    residency = residency.orElse(app.residency)
  )

  def withCanonizedIds(base: PathId = PathId.empty): AppUpdate = copy(
    id = id.map(_.canonicalPath(base)),
    dependencies = dependencies.map(_.map(_.canonicalPath(base)))
  )
}

object AppUpdate {
  implicit val appUpdateValidator = validator[AppUpdate] { appUp =>
    appUp.id is valid
    appUp.dependencies is valid
    appUp.upgradeStrategy is valid
    appUp.storeUrls is optional(every(urlCanBeResolvedValidator))
    appUp.portDefinitions is optional(PortDefinitions.portDefinitionsValidator)
    appUp.fetch is optional(every(fetchUriIsValid))
    appUp.container.each is valid
    appUp.residency is valid
    appUp.mem should optional(be >= 0.0)
    appUp.cpus should optional(be >= 0.0)
    appUp.instances should optional(be >= 0)
    appUp.disk should optional(be >= 0.0)
  }
}
