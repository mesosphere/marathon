package mesosphere.marathon
package core.group.impl

import java.time.OffsetDateTime
import javax.inject.Provider

import akka.event.EventStream
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.event.{ GroupChangeFailed, GroupChangeSuccess }
import mesosphere.marathon.core.group.{ GroupManager, GroupManagerConfig }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.GroupRepository
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
    deploymentService: Provider[DeploymentService])(implicit eventStream: EventStream, ctx: ExecutionContext) extends GroupManager with StrictLogging {
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
        val unversioned = assignDynamicServicePorts(from, change(from))
        val to = GroupVersioningUtil.updateVersionInfoForChangedApps(version, from, unversioned)
        Validation.validateOrThrow(to)(RootGroup.rootGroupValidator(config.availableFeatures))
        val plan = DeploymentPlan(from, to, version, toKill)
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

  @SuppressWarnings(Array("all")) // async/await
  override def refreshGroupCache(): Future[Done] = async {
    // propagation of reset group caches on repository is needed,
    // because manager and repository are holding own caches
    await(groupRepository.refreshGroupCache())
    val currentRoot = await(groupRepository.root())
    root := currentRoot
    Done
  }
}
