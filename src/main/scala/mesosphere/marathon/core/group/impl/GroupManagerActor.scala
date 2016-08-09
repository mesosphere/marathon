package mesosphere.marathon.core.group.impl

import java.net.URL
import javax.inject.Provider

import akka.actor.{ Actor, ActorLogging, Props }
import akka.event.EventStream
import akka.pattern.pipe
import mesosphere.marathon._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.event.{ GroupChangeFailed, GroupChangeSuccess }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.io.PathFun
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.{ AppDefinition, Container, PortDefinition, _ }
import mesosphere.marathon.upgrade.{ DeploymentPlan, GroupVersioningUtil, ResolveArtifacts }
import mesosphere.util.CapConcurrentExecutions
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{ Failure, Success }

private[group] object GroupManagerActor {
  sealed trait Request

  // Replies with Option[AppDefinition]
  case class GetAppWithId(id: PathId) extends Request

  // Replies with Option[Group]
  case class GetGroupWithId(id: PathId) extends Request

  // Replies with Option[Group]
  case class GetGroupWithVersion(id: PathId, version: Timestamp) extends Request

  // Replies with Group
  case object GetRootGroup extends Request

  // Replies with DeploymentPlan
  case class GetUpgrade(
    gid: PathId,
    change: Group => Group,
    version: Timestamp = Timestamp.now(),
    force: Boolean = false,
    toKill: Map[PathId, Iterable[Task]] = Map.empty) extends Request

  // Replies with Iterable[Timestamp]
  case class GetAllVersions(id: PathId) extends Request

  def props(
    serializeUpdates: CapConcurrentExecutions,
    scheduler: Provider[DeploymentService],
    groupRepo: GroupRepository,
    appRepo: AppRepository,
    storage: StorageProvider,
    config: MarathonConf,
    eventBus: EventStream): Props = {
    Props(new GroupManagerActor(
      serializeUpdates,
      scheduler,
      groupRepo,
      appRepo,
      storage,
      config,
      eventBus))
  }
}

private[impl] class GroupManagerActor(
    serializeUpdates: CapConcurrentExecutions,
    // a Provider has to be used to resolve a cyclic dependency between CoreModule and MarathonModule.
    // Once MarathonSchedulerService is in CoreModule, the Provider could be removed
    schedulerProvider: Provider[DeploymentService],
    groupRepo: GroupRepository,
    appRepo: AppRepository,
    storage: StorageProvider,
    config: MarathonConf,
    eventBus: EventStream) extends Actor with ActorLogging with PathFun {
  import GroupManagerActor._
  import context.dispatcher

  private[this] val log = LoggerFactory.getLogger(getClass.getName)
  private var scheduler: DeploymentService = _

  override def preStart(): Unit = {
    super.preStart()
    scheduler = schedulerProvider.get()
  }

  override def receive: Receive = {
    case GetAppWithId(id) => getApp(id).pipeTo(sender())
    case GetRootGroup => getRootGroup.pipeTo(sender())
    case GetGroupWithId(id) => getGroupWithId(id).pipeTo(sender())
    case GetGroupWithVersion(id, version) => getGroupWithVersion(id, version).pipeTo(sender())
    case GetUpgrade(gid, change, version, force, toKill) =>
      getUpgrade(gid, change, version, force, toKill).pipeTo(sender())
    case GetAllVersions(id) => getVersions(id).pipeTo(sender())
  }

  private[this] def getApp(id: PathId): Future[Option[AppDefinition]] = {
    getRootGroup().map(_.app(id))
  }

  private[this] def getRootGroup(): Future[Group] = {
    groupRepo.group(groupRepo.zkRootName).map(_.getOrElse(Group.empty))
  }

  private[this] def getGroupWithId(id: PathId): Future[Option[Group]] = {
    getRootGroup().map(_.findGroup(_.id == id))
  }

  private[this] def getGroupWithVersion(id: PathId, version: Timestamp): Future[Option[Group]] = {
    groupRepo.group(groupRepo.zkRootName, version).map {
      _.flatMap(_.findGroup(_.id == id))
    }
  }

  private[this] def getUpgrade(
    gid: PathId,
    change: Group => Group,
    version: Timestamp,
    force: Boolean,
    toKill: Map[PathId, Iterable[Task]]): Future[DeploymentPlan] = {
    serializeUpdates {
      log.info(s"Upgrade group id:$gid version:$version with force:$force")

      def storeUpdatedApps(plan: DeploymentPlan): Future[Unit] = {
        plan.affectedApplicationIds.foldLeft(Future.successful(())) { (savedFuture, currentId) =>
          plan.target.app(currentId) match {
            case Some(newApp) =>
              log.info(s"[${newApp.id}] storing new app version ${newApp.version}")
              appRepo.store(newApp).map(_ => ())
            case None =>
              log.info(s"[$currentId] expunging app")
              // this means that destroyed apps are immediately gone -- even if there are still tasks running for
              // this app. We should improve this in the future.
              appRepo.expunge(currentId).map(_ => ())
          }
        }
      }

      val deployment = for {
        from <- getRootGroup()
        (toUnversioned, resolve) <- resolveStoreUrls(assignDynamicServicePorts(from, change(from)))
        to = GroupVersioningUtil.updateVersionInfoForChangedApps(version, from, toUnversioned)
        _ = validateOrThrow(to)(Group.validRootGroup(config.maxApps.get))
        plan = DeploymentPlan(from, to, resolve, version, toKill)
        _ = validateOrThrow(plan)(DeploymentPlan.deploymentPlanValidator(config))
        _ = log.info(s"Computed new deployment plan:\n$plan")
        _ <- scheduler.deploy(plan, force)
        _ <- storeUpdatedApps(plan)
        _ <- groupRepo.store(groupRepo.zkRootName, plan.target)
        _ = log.info(s"Updated groups/apps according to deployment plan ${plan.id}")
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

  private[this] def getVersions(id: PathId): Future[Iterable[Timestamp]] = {
    groupRepo.listVersions(groupRepo.zkRootName).flatMap { versions =>
      Future.sequence(versions.map(groupRepo.group(groupRepo.zkRootName, _))).map {
        _.collect {
          case Some(group) if group.group(id).isDefined => group.version
        }
      }
    }
  }

  private[this] def resolveStoreUrls(group: Group): Future[(Group, Seq[ResolveArtifacts])] = {
    def url2Path(url: String): Future[(String, String)] = contentPath(new URL(url)).map(url -> _)
    Future.sequence(group.transitiveApps.flatMap(_.storeUrls).map(url2Path))
      .map(_.toMap)
      .map { paths =>
        //Filter out all items with already existing path.
        //Since the path is derived from the content itself,
        //it will only change, if the content changes.
        val downloads = mutable.Map(paths.toSeq.filterNot { case (url, path) => storage.item(path).exists }: _*)
        val actions = Seq.newBuilder[ResolveArtifacts]
        group.updateApps(group.version) { app =>
          if (app.storeUrls.isEmpty) app
          else {
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

  //scalastyle:off method.length cyclomatic.complexity
  private[impl] def assignDynamicServicePorts(from: Group, to: Group): Group = {
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

      // defined only if there are port mappings
      val newContainer: Option[Container] = for {
        c <- app.container
        d <- c.docker() if d.portMappings.isDefined
      } yield {
        val newPortMappings = d.portMappings.get.zip(servicePorts).map {
          case (portMapping, servicePort) =>
            portMapping.copy(servicePort = servicePort)
        }

        d.copy(portMappings = Some(newPortMappings))
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

    dynamicApps.foldLeft(to) { (group, app) =>
      group.updateApp(app.id, _ => app, app.version)
    }
  }
}
