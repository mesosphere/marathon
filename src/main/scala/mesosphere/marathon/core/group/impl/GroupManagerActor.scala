package mesosphere.marathon
package core.group.impl

import java.net.URL
import javax.inject.Provider

import akka.actor.{ Actor, Props }
import akka.event.EventStream
import akka.pattern.pipe
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.event.{ GroupChangeFailed, GroupChangeSuccess }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.io.PathFun
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.{ AppDefinition, PortDefinition, _ }
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.upgrade.{ DeploymentPlan, GroupVersioningUtil, ResolveArtifacts }
import mesosphere.marathon.util.WorkQueue
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{ Failure, Success }

private[group] object GroupManagerActor {
  sealed trait Request

  // Replies with Option[RunSpec]
  case class GetRunSpecWithId(id: PathId) extends Request

  // Replies with Option[AppDefinition]
  case class GetAppWithId(id: PathId) extends Request

  // Replies with Option[PodDefinition]
  case class GetPodWithId(id: PathId) extends Request

  // Replies with Option[Group]
  case class GetGroupWithId(id: PathId) extends Request

  // Replies with Option[Group]
  case class GetGroupWithVersion(id: PathId, version: Timestamp) extends Request

  // Replies with RootGroup
  case object GetRootGroup extends Request

  // Replies with DeploymentPlan
  case class GetUpgrade(
    gid: PathId,
    change: RootGroup => RootGroup,
    version: Timestamp = Timestamp.now(),
    force: Boolean = false,
    toKill: Map[PathId, Seq[Instance]] = Map.empty) extends Request

  // Replies with Seq[Timestamp]
  case class GetAllVersions(id: PathId) extends Request

  def props(
    serializeUpdates: WorkQueue,
    scheduler: Provider[DeploymentService],
    groupRepo: GroupRepository,
    storage: StorageProvider,
    config: MarathonConf,
    eventBus: EventStream)(implicit mat: Materializer): Props = {
    Props(new GroupManagerActor(
      serializeUpdates,
      scheduler,
      groupRepo,
      storage,
      config,
      eventBus))
  }
}

private[impl] class GroupManagerActor(
    serializeUpdates: WorkQueue,
    // a Provider has to be used to resolve a cyclic dependency between CoreModule and MarathonModule.
    // Once MarathonSchedulerService is in CoreModule, the Provider could be removed
    schedulerProvider: Provider[DeploymentService],
    groupRepo: GroupRepository,
    storage: StorageProvider,
    config: MarathonConf,
    eventBus: EventStream)(implicit mat: Materializer) extends Actor with PathFun {
  import GroupManagerActor._
  import context.dispatcher

  private[this] val log = LoggerFactory.getLogger(getClass.getName)
  private var scheduler: DeploymentService = _

  override def preStart(): Unit = {
    super.preStart()
    scheduler = schedulerProvider.get()
  }

  override def receive: Receive = {
    case GetRunSpecWithId(id) => getRunSpec(id).pipeTo(sender())
    case GetAppWithId(id) => getApp(id).pipeTo(sender())
    case GetPodWithId(id) => getPod(id).pipeTo(sender())
    case GetRootGroup => groupRepo.root().pipeTo(sender())
    case GetGroupWithId(id) => getGroupWithId(id).pipeTo(sender())
    case GetGroupWithVersion(id, version) => getGroupWithVersion(id, version).pipeTo(sender())
    case GetUpgrade(gid, change, version, force, toKill) =>
      getUpgrade(gid, change, version, force, toKill).pipeTo(sender())
    case GetAllVersions(id) => getVersions(id).pipeTo(sender())
  }

  private[this] def getRunSpec(id: PathId): Future[Option[RunSpec]] = {
    groupRepo.root().map { root =>
      root.app(id).orElse(root.pod(id))
    }
  }

  private[this] def getApp(id: PathId): Future[Option[AppDefinition]] = {
    groupRepo.root().map(_.app(id))
  }

  private[this] def getPod(id: PathId): Future[Option[PodDefinition]] = {
    groupRepo.root().map(_.pod(id))
  }

  private[this] def getGroupWithId(id: PathId): Future[Option[Group]] = {
    groupRepo.root().map(_.group(id))
  }

  private[this] def getGroupWithVersion(id: PathId, version: Timestamp): Future[Option[Group]] = {
    groupRepo.rootVersion(version.toOffsetDateTime).map {
      _.flatMap(_.group(id))
    }
  }

  private[this] def getUpgrade(
    gid: PathId,
    change: RootGroup => RootGroup,
    version: Timestamp,
    force: Boolean,
    toKill: Map[PathId, Seq[Instance]]): Future[DeploymentPlan] = {
    serializeUpdates {
      log.info(s"Upgrade root group version:$version with force:$force")

      val deployment = for {
        from <- groupRepo.root()
        (toUnversioned, resolve) <- resolveStoreUrls(assignDynamicServicePorts(from, change(from)))
        to = GroupVersioningUtil.updateVersionInfoForChangedApps(version, from, toUnversioned)
        _ = validateOrThrow(to)(RootGroup.valid(config.availableFeatures))
        plan = DeploymentPlan(from, to, resolve, version, toKill)
        _ = validateOrThrow(plan)(DeploymentPlan.deploymentPlanValidator())
        _ = log.info(s"Computed new deployment plan:\n$plan")
        _ <- groupRepo.storeRootVersion(plan.target, plan.createdOrUpdatedApps, plan.createdOrUpdatedPods)
        _ <- scheduler.deploy(plan, force)
        _ <- groupRepo.storeRoot(plan.target, plan.createdOrUpdatedApps,
          plan.deletedApps, plan.createdOrUpdatedPods, plan.deletedPods)
        _ = log.info(s"Updated groups/apps/pods according to deployment plan ${plan.id}")
      } yield plan

      deployment.onComplete {
        case Success(plan) =>
          log.info(s"Deployment acknowledged. Waiting to get processed:\n$plan")
          eventBus.publish(GroupChangeSuccess(gid, version.toString))
        case Failure(ex: AccessDeniedException) =>
        // If the request was not authorized, we should not publish an event
        case Failure(ex) =>
          log.warn(s"Deployment failed for change: $version", ex)
          eventBus.publish(GroupChangeFailed(gid, version.toString, ex.getMessage))
      }
      deployment
    }
  }

  private[this] def getVersions(id: PathId): Future[Seq[Timestamp]] = {
    groupRepo.rootVersions().runWith(Sink.seq).flatMap { versions =>
      Future.sequence(versions.map(groupRepo.rootVersion)).map {
        _.collect {
          case Some(group) if group.group(id).isDefined => group.version
        }
      }
    }
  }

  private[this] def resolveStoreUrls(rootGroup: RootGroup): Future[(RootGroup, Seq[ResolveArtifacts])] = {
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

  private[impl] def assignDynamicServicePorts(from: RootGroup, to: RootGroup): RootGroup = {
    val portRange = Range(config.localPortMin(), config.localPortMax())
    var taken = from.transitiveApps.flatMap(_.servicePorts) ++ to.transitiveApps.flatMap(_.servicePorts)

    def nextGlobalFreePort: Int = {
      val port = portRange.find(!taken.contains(_))
        .getOrElse(throw new PortRangeExhaustedException(
          config.localPortMin(),
          config.localPortMax()
        ))
      log.info(s"Take next configured free port: $port")
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

      // TODO(portMappings) this should apply for multiple container types
      // defined only if there are port mappings
      val newContainer = app.container.flatMap { container =>
        container.docker.map { docker =>
          val newMappings = docker.portMappings.zip(servicePorts).map {
            case (portMapping, servicePort) => portMapping.copy(servicePort = servicePort)
          }
          docker.copy(portMappings = newMappings)
        }
      }

      app.copy(
        portDefinitions = mergeServicePortsAndPortDefinitions(app.portDefinitions, servicePorts),
        container = newContainer.orElse(app.container)
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
}
