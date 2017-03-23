package mesosphere.marathon
package core.group.impl

import java.net.URL
import java.time.OffsetDateTime
import javax.inject.Provider

import akka.NotUsed
import akka.event.EventStream
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.core.deployment.{ DeploymentPlan, ResolveArtifacts }
import mesosphere.marathon.core.event.{ GroupChangeFailed, GroupChangeSuccess }
import mesosphere.marathon.core.group.{ GroupManager, GroupManagerConfig }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.upgrade.GroupVersioningUtil
import mesosphere.marathon.util.{ LockedVar, WorkQueue }

import scala.async.Async._
import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

class GroupManagerImpl(
    config: GroupManagerConfig,
    initialRoot: RootGroup,
    groupRepository: GroupRepository,
    deploymentService: Provider[DeploymentService],
    storage: StorageProvider)(implicit eventStream: EventStream, ctx: ExecutionContext) extends GroupManager with StrictLogging {
  /**
    * All updates to root() should go through this workqueue and the maxConcurrent should always be "1"
    * as we don't allow multiple updates to the root at the same time.
    */
  private[this] val serializeUpdates: WorkQueue = WorkQueue(
    "GroupManager",
    maxConcurrent = 1, maxQueueLength = config.internalMaxQueuedRootGroupUpdates())
  /**
    * Lock around the root to guarantee read-after-write consistency,
    * Even though updates go through the workqueue, we want to make sure multiple readers always read
    * the latest version of the root. This could be solved by a @volatile too, but this is more explicit.
    */
  private[this] val root = LockedVar(initialRoot)

  override def rootGroup(): RootGroup = root.get()

  override def versions(id: PathId): Source[Timestamp, NotUsed] = {
    groupRepository.rootVersions().mapAsync(Int.MaxValue) { version =>
      groupRepository.rootVersion(version)
    }.collect { case Some(g) if g.group(id).isDefined => g.version }
  }

  override def appVersions(id: PathId): Source[OffsetDateTime, NotUsed] = {
    groupRepository.appVersions(id)
  }

  override def appVersion(id: PathId, version: OffsetDateTime): Future[Option[AppDefinition]] = {
    groupRepository.appVersion(id, version)
  }

  override def podVersions(id: PathId): Source[OffsetDateTime, NotUsed] = {
    groupRepository.podVersions(id)
  }

  override def podVersion(id: PathId, version: OffsetDateTime): Future[Option[PodDefinition]] = {
    groupRepository.podVersion(id, version)
  }

  override def group(id: PathId): Option[Group] = rootGroup().group(id)

  @SuppressWarnings(Array("all")) /* async/await */
  override def group(id: PathId, version: Timestamp): Future[Option[Group]] = async {
    val root = await(groupRepository.rootVersion(version.toOffsetDateTime))
    root.flatMap(_.group(id))
  }

  override def runSpec(id: PathId): Option[RunSpec] = app(id).orElse(pod(id))

  override def app(id: PathId): Option[AppDefinition] = rootGroup().app(id)

  override def pod(id: PathId): Option[PodDefinition] = rootGroup().pod(id)

  @SuppressWarnings(Array("all")) /* async/await */
  override def updateRoot(
    id: PathId,
    change: (RootGroup) => RootGroup, version: Timestamp, force: Boolean, toKill: Map[PathId, Seq[Instance]]): Future[DeploymentPlan] = {

    // All updates to the root go through the work queue.
    val deployment = serializeUpdates {
      async {
        logger.info(s"Upgrade root group version:$version with force:$force")

        val from = rootGroup()
        val (unversioned, resolve) = await(resolveStoreUrls(assignDynamicServicePorts(from, change(from))))
        val to = GroupVersioningUtil.updateVersionInfoForChangedApps(version, from, unversioned)
        Validation.validateOrThrow(to)(RootGroup.rootGroupValidator(config.availableFeatures))
        val plan = DeploymentPlan(from, to, resolve, version, toKill)
        Validation.validateOrThrow(plan)(DeploymentPlan.deploymentPlanValidator())
        logger.info(s"Computed new deployment plan:\n$plan")
        await(groupRepository.storeRootVersion(plan.target, plan.createdOrUpdatedApps, plan.createdOrUpdatedPods))
        await(deploymentService.get().deploy(plan, force))
        await(groupRepository.storeRoot(plan.target, plan.createdOrUpdatedApps, plan.deletedApps, plan.createdOrUpdatedPods, plan.deletedPods))
        logger.info(s"Updated groups/apps/pods according to plan ${plan.id}")
        // finally update the root under the write lock.
        root := plan.target
        plan
      }
    }
    deployment.onComplete {
      case Success(plan) =>
        logger.info(s"Deployment acknowledged. Waiting to get processed:\n$plan")
        eventStream.publish(GroupChangeSuccess(id, version.toString))
      case Failure(ex: AccessDeniedException) =>
        // If the request was not authorized, we should not publish an event
        logger.warn(s"Deployment failed for change: $version", ex)
      case Failure(NonFatal(ex)) =>
        logger.warn(s"Deployment failed for change: $version", ex)
        eventStream.publish(GroupChangeFailed(id, version.toString, ex.getMessage))
    }
    deployment
  }

  private[group] def assignDynamicServicePorts(from: RootGroup, to: RootGroup): RootGroup = {
    val portRange = Range(config.localPortMin(), config.localPortMax())
    var taken = from.transitiveApps.flatMap(_.servicePorts) ++ to.transitiveApps.flatMap(_.servicePorts)

    def nextGlobalFreePort: Int = {
      val port = portRange.find(!taken.contains(_))
        .getOrElse(throw new PortRangeExhaustedException(
          config.localPortMin(),
          config.localPortMax()
        ))
      logger.info(s"Take next configured free port: $port")
      taken += port
      port
    }

    def mergeServicePortsAndPortDefinitions(
      portDefinitions: Seq[PortDefinition],
      servicePorts: Seq[Int]): Seq[PortDefinition] =
      if (portDefinitions.nonEmpty)
        portDefinitions.zipAll(servicePorts, AppDefinition.RandomPortDefinition, AppDefinition.RandomPortValue).map {
          case (portDefinition, servicePort) => portDefinition.copy(port = servicePort)
        }
      else Seq.empty

    def assignPorts(app: AppDefinition): AppDefinition = {
      //all ports that are already assigned in old app definition, but not used in the new definition
      //if the app uses dynamic ports (0), it will get always the same ports assigned
      val assignedAndAvailable = mutable.Queue(
        from.app(app.id)
          .map(_.servicePorts.filter(p => portRange.contains(p) && !app.servicePorts.contains(p)))
          .getOrElse(Nil): _*
      )

      def nextFreeServicePort: Int =
        if (assignedAndAvailable.nonEmpty) assignedAndAvailable.dequeue()
        else nextGlobalFreePort

      val servicePorts: Seq[Int] = app.servicePorts.map { port =>
        if (port == 0) nextFreeServicePort else port
      }

      val updatedContainer = app.container.find(_.portMappings.nonEmpty).map { container =>
        val newMappings = container.portMappings.zip(servicePorts).map {
          case (portMapping, servicePort) => portMapping.copy(servicePort = servicePort)
        }
        container.copyWith(portMappings = newMappings)
      }

      app.copy(
        portDefinitions = mergeServicePortsAndPortDefinitions(app.portDefinitions, servicePorts),
        container = updatedContainer.orElse(app.container)
      )
    }

    val dynamicApps: Set[AppDefinition] =
      to.transitiveApps.map {
        // assign values for service ports that the user has left "blank" (set to zero)
        case app: AppDefinition if app.hasDynamicServicePorts => assignPorts(app)
        case app: AppDefinition =>
          // Always set the ports to service ports, even if we do not have dynamic ports in our port mappings
          app.copy(
            portDefinitions = mergeServicePortsAndPortDefinitions(app.portDefinitions, app.servicePorts)
          )
      }

    dynamicApps.foldLeft(to) { (rootGroup, app) =>
      rootGroup.updateApp(app.id, _ => app, app.version)
    }
  }

  private[this] def resolveStoreUrls(rootGroup: RootGroup): Future[(RootGroup, Seq[ResolveArtifacts])] = {
    import io.PathFun._
    def url2Path(url: String): Future[(String, String)] = contentPath(new URL(url)).map(url -> _)
    Future.sequence(rootGroup.transitiveApps.flatMap(_.storeUrls).map(url2Path))
      .map(_.toMap)
      .map { paths =>
        //Filter out all items with already existing path.
        //Since the path is derived from the content itself,
        //it will only change, if the content changes.
        val downloads = mutable.Map(paths.filterNotAs { case (url, path) => storage.item(path).exists }(collection.breakOut): _*)
        val actions = Seq.newBuilder[ResolveArtifacts]
        rootGroup.updateTransitiveApps(
          PathId.empty,
          app =>
            if (app.storeUrls.isEmpty) app
            else {
              val storageUrls = app.storeUrls.map(paths).map(storage.item(_).url)
              val resolved = app.copy(fetch = app.fetch ++ storageUrls.map(FetchUri.apply(_)), storeUrls = Seq.empty)
              val appDownloads: Map[URL, String] =
                app.storeUrls
                  .flatMap { url => downloads.remove(url).map { path => new URL(url) -> path } }(collection.breakOut)
              if (appDownloads.nonEmpty) actions += ResolveArtifacts(resolved, appDownloads)
              resolved
            }, rootGroup.version) -> actions.result()
      }
  }
}
