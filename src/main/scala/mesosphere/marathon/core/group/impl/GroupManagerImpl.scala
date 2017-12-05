package mesosphere.marathon
package core.group.impl

import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Provider

import akka.event.EventStream
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
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
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

class GroupManagerImpl(
    val config: GroupManagerConfig,
    initialRoot: Option[RootGroup],
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

  @SuppressWarnings(Array("OptionGet"))
  override def rootGroup(): RootGroup =
    root.get() match { // linter:ignore:UseGetOrElseNotPatMatch
      case None =>
        root.update {
          case None =>
            val group = Await.result(groupRepository.root(), config.zkTimeoutDuration)
            registerMetrics()
            Some(group)
          case group =>
            group
        }.get
      case Some(group) => group
    }

  override def rootGroupOption(): Option[RootGroup] = root.get()

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

  override def apps(ids: Set[PathId]) = ids.map(appId => appId -> app(appId))(collection.breakOut)

  override def pod(id: PathId): Option[PodDefinition] = rootGroup().pod(id)

  @SuppressWarnings(Array("all")) /* async/await */
  override def updateRootEither[T](
    id: PathId,
    change: (RootGroup) => Future[Either[T, RootGroup]],
    version: Timestamp, force: Boolean, toKill: Map[PathId, Seq[Instance]]): Future[Either[T, DeploymentPlan]] = try {

    // All updates to the root go through the work queue.
    val maybeDeploymentPlan: Future[Either[T, DeploymentPlan]] = serializeUpdates {
      logger.info(s"Upgrade root group version:$version with force:$force")

      val from = rootGroup()
      async {
        val changedGroup = await(change(from))
        changedGroup match {
          case Left(left) =>
            Left(left)
          case Right(changed) =>
            val unversioned = AssignDynamicServiceLogic.assignDynamicServicePorts(
              Range.inclusive(config.localPortMin(), config.localPortMax()),
              from,
              changed)
            val withVersionedApps = GroupVersioningUtil.updateVersionInfoForChangedApps(version, from, unversioned)
            val withVersionedAppsPods = GroupVersioningUtil.updateVersionInfoForChangedPods(version, from, withVersionedApps)
            Validation.validateOrThrow(withVersionedAppsPods)(RootGroup.rootGroupValidator(config.availableFeatures))
            val plan = DeploymentPlan(from, withVersionedAppsPods, version, toKill)
            Validation.validateOrThrow(plan)(DeploymentPlan.deploymentPlanValidator())
            logger.info(s"Computed new deployment plan for ${plan.targetIdsString}:\n$plan")
            await(groupRepository.storeRootVersion(plan.target, plan.createdOrUpdatedApps, plan.createdOrUpdatedPods))
            await(deploymentService.get().deploy(plan, force))
            await(groupRepository.storeRoot(plan.target, plan.createdOrUpdatedApps, plan.deletedApps, plan.createdOrUpdatedPods, plan.deletedPods))
            logger.info(s"Updated groups/apps/pods according to plan ${plan.id} for ${plan.targetIdsString}")
            // finally update the root under the write lock.
            root := Option(plan.target)
            Right(plan)
        }
      }
    }

    maybeDeploymentPlan.onComplete {
      case Success(Right(plan)) =>
        logger.info(s"Deployment ${plan.id}:${plan.version} for ${plan.targetIdsString} acknowledged. Waiting to get processed")
        eventStream.publish(GroupChangeSuccess(id, version.toString))
      case Success(Left(_)) =>
        ()
      case Failure(ex: AccessDeniedException) =>
        // If the request was not authorized, we should not publish an event
        logger.warn(s"Deployment failed for change: $version", ex)
      case Failure(NonFatal(ex)) =>
        logger.warn(s"Deployment failed for change: $version", ex)
        eventStream.publish(GroupChangeFailed(id, version.toString, ex.getMessage))
    }
    maybeDeploymentPlan
  } catch {
    case NonFatal(ex) => Future.failed(ex)
  }

  @SuppressWarnings(Array("all")) // async/await
  override def invalidateGroupCache(): Future[Done] = async {
    root := None

    // propagation of reset group caches on repository is needed,
    // because manager and repository are holding own caches
    await(groupRepository.invalidateGroupCache())

    // force fetching of the root group from the group repository
    rootGroup()
    Done
  }

  private[this] val metricsRegistered: AtomicBoolean = new AtomicBoolean(false)
  private[this] def registerMetrics(): Unit = {
    if (metricsRegistered.compareAndSet(false, true)) {
      // We've already released metrics using these names, so we can't use the Metrics.* methods
      Kamon.metrics.gauge("service.mesosphere.marathon.app.count") {
        rootGroupOption().foldLeft(0L) { (_, group) =>
          group.transitiveApps.size.toLong
        }
      }

      Kamon.metrics.gauge("service.mesosphere.marathon.group.count") {
        rootGroupOption().foldLeft(0L) { (_, group) =>
          group.transitiveGroupsById.size.toLong
        }
      }
    }
  }
}
