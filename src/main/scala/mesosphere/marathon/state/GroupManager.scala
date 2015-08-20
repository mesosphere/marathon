package mesosphere.marathon.state

import java.lang.{ Integer => JInt }
import java.net.URL
import javax.inject.{ Inject, Named }

import akka.event.EventStream
import com.google.inject.Singleton
import mesosphere.marathon.api.v2.{ BeanValidation, ModelValidation }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event.{ EventModule, GroupChangeFailed, GroupChangeSuccess }
import mesosphere.marathon.io.PathFun
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade._
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, ModuleNames, PortRangeExhaustedException }
import mesosphere.util.SerializeExecution
import mesosphere.util.ThreadPoolContext.context
import org.apache.log4j.Logger

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{ Failure, Success }

/**
  * The group manager is the facade for all group related actions.
  * It persists the state of a group and initiates deployments.
  */
class GroupManager @Singleton @Inject() (
    @Named(ModuleNames.NAMED_SERIALIZE_GROUP_UPDATES) serializeUpdates: SerializeExecution,
    scheduler: MarathonSchedulerService,
    taskTracker: TaskTracker,
    groupRepo: GroupRepository,
    appRepo: AppRepository,
    storage: StorageProvider,
    config: MarathonConf,
    @Named(EventModule.busName) eventBus: EventStream) extends PathFun {

  private[this] val log = Logger.getLogger(getClass.getName)
  private[this] val zkName = groupRepo.zkRootName

  def rootGroup(): Future[Group] =
    groupRepo.group(zkName).map(_.getOrElse(Group.empty))

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
    force: Boolean = false): Future[DeploymentPlan] =
    upgrade(gid, _.update(gid, fn, version), version, force)

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
    toKill: Set[MarathonTask] = Set.empty): Future[DeploymentPlan] =
    upgrade(appId.parent, _.updateApp(appId, fn, version), version, force, Map(appId -> toKill))

  private def upgrade(
    gid: PathId,
    change: Group => Group,
    version: Timestamp = Timestamp.now(),
    force: Boolean = false,
    toKill: Map[PathId, Set[MarathonTask]] = Map.empty): Future[DeploymentPlan] = serializeUpdates {

    log.info(s"Upgrade group id:$gid version:$version with force:$force")

    /**
      * The VersionInfo contains information relating to the last change of a certain type.
      * This methods makes sure that the version info is calculated correctly.
      */
    def updateAppVersionInfo(maybeOldApp: Option[AppDefinition], newApp: AppDefinition): AppDefinition = {
      val newVersionInfo = maybeOldApp match {
        case None =>
          log.info(s"[${newApp.id}]: new app detected")
          AppDefinition.VersionInfo.forNewConfig(newVersion = version)
        case Some(oldApp) =>
          if (oldApp.isUpgrade(newApp)) {
            log.info(s"[${newApp.id}]: upgrade detected for app (oldVersion ${oldApp.versionInfo})")
            oldApp.versionInfo.withConfigChange(newVersion = version)
          }
          else {
            log.info(s"[${newApp.id}]: scaling op detected for app (oldVersion ${oldApp.versionInfo})")
            oldApp.versionInfo.withScaleOrRestartChange(newVersion = version)
          }
      }

      log.info(s"[${newApp.id}]: new version ${newVersionInfo}")
      newApp.copy(versionInfo = newVersionInfo)
    }

    def updateVersionInfo(from: Group, to: Group): Group = {
      val originalApps = from.transitiveApps.map(app => app.id -> app).toMap
      val updatedTargetApps = to.transitiveApps.map { newApp =>
        updateAppVersionInfo(originalApps.get(newApp.id), newApp)
      }
      updatedTargetApps.foldLeft(to) { (resultGroup, updatedApp) =>
        resultGroup.updateApp(updatedApp.id, _ => updatedApp, version)
      }
    }

    def storeUpdatedApps(plan: DeploymentPlan): Future[Unit] = {
      plan.affectedApplicationIds.foldLeft(Future.successful(())) { (savedFuture, currentId) =>
        plan.target.app(currentId) match {
          case Some(newApp) =>
            log.info(s"[${newApp.id}] storing new app version ${newApp.version}")
            appRepo.store(newApp).map (_ => ())
          case None =>
            log.info(s"[$currentId] expunging app")
            // remove??
            appRepo.expunge(currentId).map (_ => ())
        }
      }
    }

    val deployment = for {
      from <- rootGroup()
      (toUnversioned, resolve) <- resolveStoreUrls(assignDynamicServicePorts(from, change(from)))
      to = updateVersionInfo(from, toUnversioned)
      _ = BeanValidation.requireValid(ModelValidation.checkGroup(to, "", PathId.empty))
      plan = DeploymentPlan(from, to, resolve, version, toKill)
      _ <- scheduler.deploy(plan, force)
      _ <- storeUpdatedApps(plan)
      _ <- groupRepo.store(zkName, plan.target)
    } yield plan

    deployment.onComplete {
      case Success(plan) =>
        log.info(s"Deployment acknowledged. Waiting to get processed: $plan")
        eventBus.publish(GroupChangeSuccess(gid, version.toString))
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
        group.updateApp(group.version) { app =>
          if (app.storeUrls.isEmpty) app else {
            val storageUrls = app.storeUrls.map(paths).map(storage.item(_).url)
            val resolved = app.copy(uris = app.uris ++ storageUrls, storeUrls = Seq.empty)
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
    var taken = from.transitiveApps.flatMap(_.ports)

    def nextGlobalFreePort: JInt = synchronized {
      val port = portRange.find(!taken.contains(_))
        .getOrElse(throw new PortRangeExhaustedException(
          config.localPortMin(),
          config.localPortMax()
        ))
      log.info(s"Take next configured free port: $port")
      taken += port
      port
    }

    def assignPorts(app: AppDefinition): AppDefinition = {
      val alreadyAssigned = mutable.Queue(
        from.app(app.id)
          .map(_.ports.filter(p => portRange.contains(p)))
          .getOrElse(Nil): _*
      )

      def nextFreeAppPort: JInt =
        if (alreadyAssigned.nonEmpty) alreadyAssigned.dequeue()
        else nextGlobalFreePort

      val servicePorts: Seq[JInt] = app.servicePorts.map { port =>
        if (port == 0) nextFreeAppPort else new JInt(port)
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
        ports = servicePorts,
        container = newContainer.orElse(app.container)
      )
    }

    val dynamicApps: Set[AppDefinition] =
      to.transitiveApps.map {
        case app: AppDefinition if app.hasDynamicPort => assignPorts(app)
        case app: AppDefinition =>
          // Always set the ports to service ports, even if we do not have dynamic ports in our port mappings
          app.copy(ports = app.servicePorts.map(Integer.valueOf))
      }

    dynamicApps.foldLeft(to) { (group, app) =>
      group.updateApp(app.id, _ => app, app.version)
    }
  }
}
