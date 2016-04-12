package mesosphere.marathon.state

import java.net.URL
import javax.inject.{ Inject, Named }

import akka.event.EventStream
import com.google.inject.Singleton
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.event.{ EventModule, GroupChangeFailed, GroupChangeSuccess }
import mesosphere.marathon.io.PathFun
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.upgrade._
import mesosphere.marathon._
import mesosphere.util.CapConcurrentExecutions
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{ Failure, Success }

/**
  * The group manager is the facade for all group related actions.
  * It persists the state of a group and initiates deployments.
  */
@Singleton
class GroupManager @Inject() (
    @Named(ModuleNames.SERIALIZE_GROUP_UPDATES) serializeUpdates: CapConcurrentExecutions,
    scheduler: MarathonSchedulerService,
    groupRepo: GroupRepository,
    appRepo: AppRepository,
    storage: StorageProvider,
    config: MarathonConf,
    @Named(EventModule.busName) eventBus: EventStream) extends PathFun {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)
  private[this] val zkName = groupRepo.zkRootName

  def rootGroup(): Future[Group] = {
    groupRepo.group(zkName).map(_.getOrElse(Group.empty))
  }

  /**
    * Get all available versions for given group identifier.
    * @param id the identifier of the group.
    * @return the list of versions of this object.
    */
  def versions(id: PathId): Future[Iterable[Timestamp]] = {
    groupRepo.listVersions(zkName).flatMap { versions =>
      Future.sequence(versions.map(groupRepo.group(zkName, _))).map {
        _.collect {
          case Some(group) if group.group(id).isDefined => group.version
        }
      }
    }
  }

  /**
    * Get a specific group by its id.
    * @param id the id of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: PathId): Future[Option[Group]] = {
    rootGroup().map(_.findGroup(_.id == id))
  }

  /**
    * Get a specific group with a specific version.
    * @param id the identifier of the group.
    * @param version the version of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: PathId, version: Timestamp): Future[Option[Group]] = {
    groupRepo.group(zkName, version).map {
      _.flatMap(_.findGroup(_.id == id))
    }
  }

  /**
    * Get a specific app definition by its id.
    * @param id the id of the app.
    * @return the app uf ut is found, otherwise false
    */
  def app(id: PathId): Future[Option[AppDefinition]] = {
    rootGroup().map(_.app(id))
  }

  /**
    * Update a group with given identifier.
    * The change of the group is defined by a change function.
    * The complete tree gets the given version.
    * The change could take time to get deployed.
    * For this reason, we return the DeploymentPlan as result, which can be queried in the marathon scheduler.
    *
    * @param gid the id of the group to change.
    * @param version the new version of the group, after the change has applied.
    * @param fn the update function, which is applied to the group identified by given id
    * @param force only one update can be applied to applications at a time. with this flag
    *              one can control, to stop a current deployment and start a new one.
    * @return the deployment plan which will be executed.
    */
  def update(
    gid: PathId,
    fn: Group => Group,
    version: Timestamp = Timestamp.now(),
    force: Boolean = false,
    toKill: Map[PathId, Iterable[Task]] = Map.empty): Future[DeploymentPlan] =
    upgrade(gid, _.update(gid, fn, version), version, force, toKill)

  /**
    * Update application with given identifier and update function.
    * The change could take time to get deployed.
    * For this reason, we return the DeploymentPlan as result, which can be queried in the marathon scheduler.
    *
    * @param appId the identifier of the application
    * @param fn the application change function
    * @param version the version of the change
    * @param force if the change has to be forced.
    * @return the deployment plan which will be executed.
    */
  def updateApp(
    appId: PathId,
    fn: Option[AppDefinition] => AppDefinition,
    version: Timestamp = Timestamp.now(),
    force: Boolean = false,
    toKill: Iterable[Task] = Iterable.empty): Future[DeploymentPlan] =
    upgrade(appId.parent, _.updateApp(appId, fn, version), version, force, Map(appId -> toKill))

  private def upgrade(
    gid: PathId,
    change: Group => Group,
    version: Timestamp = Timestamp.now(),
    force: Boolean = false,
    toKill: Map[PathId, Iterable[Task]] = Map.empty): Future[DeploymentPlan] = serializeUpdates {

    log.info(s"Upgrade group id:$gid version:$version with force:$force")

    def storeUpdatedApps(plan: DeploymentPlan): Future[Unit] = {
      plan.affectedApplicationIds.foldLeft(Future.successful(())) { (savedFuture, currentId) =>
        plan.target.app(currentId) match {
          case Some(newApp) =>
            log.info(s"[${newApp.id}] storing new app version ${newApp.version}")
            appRepo.store(newApp).map (_ => ())
          case None =>
            log.info(s"[$currentId] expunging app")
            // this means that destroyed apps are immediately gone -- even if there are still tasks running for
            // this app. We should improve this in the future.
            appRepo.expunge(currentId).map (_ => ())
        }
      }
    }

    val deployment = for {
      from <- rootGroup()
      (toUnversioned, resolve) <- resolveStoreUrls(assignDynamicServicePorts(from, change(from)))
      to = GroupVersioningUtil.updateVersionInfoForChangedApps(version, from, toUnversioned)
      _ = validateOrThrow(to)(Group.validRootGroup(config.maxApps.get))
      plan = DeploymentPlan(from, to, resolve, version, toKill)
      _ = validateOrThrow(plan)
      _ = log.info(s"Computed new deployment plan:\n$plan")
      _ <- scheduler.deploy(plan, force)
      _ <- storeUpdatedApps(plan)
      _ <- groupRepo.store(zkName, plan.target)
      _ = log.info(s"Updated groups/apps according to deployment plan ${plan.id}")
    } yield plan

    deployment.onComplete {
      case Success(plan) =>
        log.info(s"Deployment acknowledged. Waiting to get processed:\n$plan")
        eventBus.publish(GroupChangeSuccess(gid, version.toString))
      case Failure(ex: AccessDeniedException) => // If the request was not authorized, we should not publish an event
      case Failure(ex) =>
        log.warn(s"Deployment failed for change: $version", ex)
        eventBus.publish(GroupChangeFailed(gid, version.toString, ex.getMessage))
    }
    deployment
  }

  private[state] def resolveStoreUrls(group: Group): Future[(Group, Seq[ResolveArtifacts])] = {
    def url2Path(url: String): Future[(String, String)] = contentPath(new URL(url)).map(url -> _)
    Future.sequence(group.transitiveApps.flatMap(_.storeUrls).map(url2Path))
      .map(_.toMap)
      .map { paths =>
        //Filter out all items with already existing path.
        //Since the path is derived from the content itself,
        //it will only change, if the content changes.
        val downloads = mutable.Map(paths.toSeq.filterNot{ case (url, path) => storage.item(path).exists }: _*)
        val actions = Seq.newBuilder[ResolveArtifacts]
        group.updateApps(group.version) { app =>
          if (app.storeUrls.isEmpty) app else {
            val storageUrls = app.storeUrls.map(paths).map(storage.item(_).url)
            val resolved = app.copy(fetch = app.fetch ++ storageUrls.map(FetchUri.apply(_)), storeUrls = Seq.empty)
            val appDownloads: Map[URL, String] =
              app.storeUrls
                .flatMap { url => downloads.remove(url).map { path => new URL(url) -> path } }.toMap
            if (appDownloads.nonEmpty) actions += ResolveArtifacts(resolved, appDownloads)
            resolved
          }
        } -> actions.result()
      }
  }

  //scalastyle:off method.length
  private[state] def assignDynamicServicePorts(from: Group, to: Group): Group = {
    val portRange = Range(config.localPortMin(), config.localPortMax())
    var taken = from.transitiveApps.flatMap(_.portNumbers) ++ to.transitiveApps.flatMap(_.portNumbers)

    def nextGlobalFreePort: Int = synchronized {
      val port = portRange.find(!taken.contains(_))
        .getOrElse(throw new PortRangeExhaustedException(
          config.localPortMin(),
          config.localPortMax()
        ))
      log.info(s"Take next configured free port: $port")
      taken += port
      port
    }

    def mergeServicePortsAndPortDefinitions(portDefinitions: Seq[PortDefinition], servicePorts: Seq[Int]) = {
      portDefinitions.zipAll(servicePorts, AppDefinition.RandomPortDefinition, AppDefinition.RandomPortValue).map {
        case (portDefinition, servicePort) => portDefinition.copy(port = servicePort)
      }
    }

    def assignPorts(app: AppDefinition): AppDefinition = {

      //all ports that are already assigned in old app definition, but not used in the new definition
      //if the app uses dynamic ports (0), it will get always the same ports assigned
      val assignedAndAvailable = mutable.Queue(
        from.app(app.id)
          .map(_.portNumbers.filter(p => portRange.contains(p) && !app.servicePorts.contains(p)))
          .getOrElse(Nil): _*
      )

      def nextFreeAppPort: Int =
        if (assignedAndAvailable.nonEmpty) assignedAndAvailable.dequeue()
        else nextGlobalFreePort

      val servicePorts: Seq[Int] = app.servicePorts.map { port =>
        if (port == 0) nextFreeAppPort else port
      }

      // defined only if there are port mappings
      val newContainer: Option[Container] = for {
        c <- app.container
        d <- c.docker
        pms <- d.portMappings
      } yield {
        val mappings = pms.zip(servicePorts).map {
          case (pm, sp) => pm.copy(servicePort = sp)
        }
        c.copy(
          docker = Some(d.copy(
            portMappings = Some(mappings)))
        )
      }

      app.copy(
        portDefinitions = mergeServicePortsAndPortDefinitions(app.portDefinitions, servicePorts),
        container = newContainer.orElse(app.container)
      )
    }

    val dynamicApps: Set[AppDefinition] =
      to.transitiveApps.map {
        case app: AppDefinition if app.hasDynamicPort => assignPorts(app)
        case app: AppDefinition =>
          // Always set the ports to service ports, even if we do not have dynamic ports in our port mappings
          app.copy(
            portDefinitions = mergeServicePortsAndPortDefinitions(app.portDefinitions, app.servicePorts)
          )
      }

    dynamicApps.foldLeft(to) { (group, app) =>
      group.updateApp(app.id, _ => app, app.version)
    }
  }
}
