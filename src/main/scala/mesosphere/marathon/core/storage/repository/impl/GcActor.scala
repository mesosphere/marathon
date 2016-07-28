package mesosphere.marathon.core.storage.repository.impl

// scalastyle:off
import java.time.OffsetDateTime

import akka.Done
import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.pattern._
import akka.stream.Materializer
import mesosphere.marathon.core.storage.repository.impl.GcActor.{ CompactDone, Message, RunGC, ScanDone, StoreApp, StoreEntity, StorePlan, StoreRoot }
import mesosphere.marathon.state.{ Group, PathId }
import mesosphere.marathon.stream.Sink
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.async.Async.{ async, await }
import scala.collection.{ SortedSet, mutable }
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.control.NonFatal
// scalastyle:on

/**
  * Actor which manages Garbage Collection. Garbage Collection may be triggered by anything
  * but we currently trigger it from DeploymentRepository.delete as DeploymentPlans "are at the top" of the
  * dependency graph: deploymentPlan -> root*2 -> apps.
  *
  * The actor is very conservative about deleting and will prefer extra objects (that will likely eventually
  * be deleted) than having objects that refer to objects that no longer exist.
  *
  * The actor has three phases:
  * - Idle (nothing happening at all)
  * - Scanning
  * - Compacting
  *
  * Scan Phase
  * - if the total number of root versions is < maxVersions, do nothing
  * - if the total number of root versions is > maxVersions and every root is referred to by a deployment plan,
  *   do nothing.
  * - Otherwise, the oldest unused roots are picked for deletion (to get back under the cap) and we will then
  *   scan look at the [[StoredGroup]]s (we don't need to retrieve/resolve them) and find all of the app
  *   versions they are using.
  *   - We then compare this against the set of app ids that exist and find any app ids that
  *     no root refers to.
  *   - We also scan through the apps that are in use and find only the apps that have more than the cap.
  *     We take these apps and remove any versions which are not in use by any root.
  * - While the scan phase is in progress, all requests to store a Plan/Group/App will be tracked so
  *   that we can remove them from the set of deletions.
  * - When the scan is complete, we will take the set of deletions and enter into the Compacting phase.
  * - If scan fails for any reason, either return to Idle (if no further GCs were requested)
  *   or back into Scanning (if further GCs were requested). The additional GCs are coalesced into a single
  *   GC run.
  *
  * Compaction Phase:
  * - Go actually delete the objects from the database in the background.
  * - While deleting, check any store requests to see if they _could_ conflict with the in progress deletions.
  *   If and only if there is a conflict, 'block' the store (via a promise/future) until the deletion completes.
  *   If there isn't a conflict, let it save anyway.
  * - When the deletion completes, inform any attempts to store a potential conflict that it may now proceed,
  *   then transition back to idle or scanning depending on whether or not one or more additional GC Requests
  *   were sent to the actor.
  * - If compact fails for any reason,  transition back to idle or scanning depending on whether or not one or
  *   more additional GC Requests were sent to the actor.
  */
private class GcActor[K, C, S](
  val deploymentRepository: DeploymentRepositoryImpl[K, C, S],
  val groupRepository: StoredGroupRepositoryImpl[K, C, S],
  val appRepository: AppRepositoryImpl[K, C, S],
  val maxVersions: Int)(implicit val mat: Materializer, val ctx: ExecutionContext)
    extends Actor with ActorLogging with ScanBehavior[K, C, S] with CompactBehavior[K, C, S] {
  override val receive: Receive = idle

  def idle: Receive = {
    case RunGC =>
      // scan runs in the execution context, not in the actor's context.
      scan().pipeTo(self)
      context.become(scanning(
        Set.empty,
        new mutable.HashMap[PathId, mutable.Set[OffsetDateTime]] with mutable.MultiMap[PathId, OffsetDateTime],
        Set.empty, gcRequested = false))
    case StoreEntity(promise) =>
      // all attempts to store can proceed.
      promise.success(Done)
    case _: Message =>
    // ignore other messages
  }
}

private trait ScanBehavior[K, C, S] { this: Actor with ActorLogging with CompactBehavior[K, C, S] =>
  implicit val mat: Materializer
  implicit val ctx: ExecutionContext
  val maxVersions: Int
  val appRepository: AppRepositoryImpl[K, C, S]
  val groupRepository: StoredGroupRepositoryImpl[K, C, S]
  val deploymentRepository: DeploymentRepositoryImpl[K, C, S]
  val self: ActorRef

  def computeActualDeletions(
    appsStored: Set[PathId],
    appVersionsStored: mutable.HashMap[PathId, mutable.Set[OffsetDateTime]] with mutable.MultiMap[PathId, OffsetDateTime], // scalastyle:off
    rootsStored: Set[OffsetDateTime],
    scanDone: ScanDone): (Set[PathId], Map[PathId, SortedSet[OffsetDateTime]], Set[OffsetDateTime]) = {
    val ScanDone(appsToDelete, appVersionsToDelete, rootVersionsToDelete) = scanDone
    val appsToActuallyDelete = appsToDelete.diff(appsStored)
    val appVersionsToActuallyDelete = appVersionsToDelete.map {
      case (id, versions) =>
        appVersionsStored.get(id).fold(id -> versions) { versionsStored =>
          id -> versions.diff(versionsStored)
        }
    }
    val rootsToActuallyDelete = rootVersionsToDelete.diff(rootsStored)
    (appsToActuallyDelete, appVersionsToActuallyDelete, rootsToActuallyDelete)
  }

  def addAppVersions(
    apps: Map[PathId, OffsetDateTime],
    appVersionsStored: mutable.HashMap[PathId, mutable.Set[OffsetDateTime]] with mutable.MultiMap[PathId, OffsetDateTime]): Unit = { // scalastyle:off
    apps.foreach { case (id, appVersion) => appVersionsStored.addBinding(id, appVersion) }
  }

  def scanning(
    appsStored: Set[PathId],
    appVersionsStored: mutable.HashMap[PathId, mutable.Set[OffsetDateTime]] with mutable.MultiMap[PathId, OffsetDateTime], // scalastyle:off
    rootsStored: Set[OffsetDateTime],
    gcRequested: Boolean): Receive = {
    case RunGC =>
      context.become(scanning(appsStored, appVersionsStored, rootsStored, gcRequested = true))
    case done: ScanDone =>
      val (appsToDelete, appVersionsToDelete, rootsToDelete) =
        computeActualDeletions(appsStored, appVersionsStored, rootsStored, done)
      compact(appsToDelete, appVersionsToDelete, rootsToDelete).pipeTo(self)
      context.become(compacting(appsToDelete, appVersionsToDelete, rootsToDelete, Nil, gcRequested))
    case StoreApp(appId, Some(version), promise) =>
      promise.success(Done)
      context.become(scanning(appsStored, appVersionsStored.addBinding(appId, version), rootsStored, gcRequested))
    case StoreApp(appId, _, promise) =>
      promise.success(Done)
      context.become(scanning(appsStored + appId, appVersionsStored, rootsStored, gcRequested))
    case StoreRoot(root, promise) =>
      promise.success(Done)
      addAppVersions(root.transitiveAppIds, appVersionsStored)
      context.become(scanning(appsStored, appVersionsStored, rootsStored + root.version, gcRequested))
    case StorePlan(plan, promise) =>
      promise.success(Done)
      addAppVersions(plan.original.transitiveAppsById.mapValues(_.version.toOffsetDateTime), appVersionsStored)
      addAppVersions(plan.target.transitiveAppsById.mapValues(_.version.toOffsetDateTime), appVersionsStored)
      val newRootsStored = rootsStored ++
        Set(plan.original.version.toOffsetDateTime, plan.target.version.toOffsetDateTime)
      context.become(scanning(appsStored, appVersionsStored, newRootsStored, gcRequested))
    case _: Message =>
    // ignore
  }

  def scan(): Future[ScanDone] = {
    async {
      val rootVersions = await(groupRepository.rootVersions().runWith(Sink.sortedSet))
      if (rootVersions.size <= maxVersions) {
        ScanDone(Set.empty, Map.empty, Set.empty)
      } else {
        val currentRootFuture = groupRepository.root()
        val storedPlansFuture = deploymentRepository.lazyAll().runWith(Sink.list)
        val currentRoot = await(currentRootFuture)
        val storedPlans = await(storedPlansFuture)

        val inUseRootVersions: SortedSet[OffsetDateTime] = storedPlans.flatMap { plan =>
          Seq(plan.originalVersion, plan.targetVersion)
        }(collection.breakOut)

        val deletionCandidates = rootVersions.diff(inUseRootVersions + currentRoot.version.toOffsetDateTime)

        if (deletionCandidates.isEmpty) {
          ScanDone(Set.empty, Map.empty, Set.empty)
        } else {
          val rootsToDelete = deletionCandidates.take(rootVersions.size - maxVersions)
          if (rootsToDelete.isEmpty) {
            ScanDone(Set.empty, Map.empty, Set.empty)
          } else {
            await(scanUnusedApps(rootsToDelete, storedPlans, currentRoot))
          }
        }
      }
    }
  }

  private def scanUnusedApps(
    rootsToDelete: Set[OffsetDateTime],
    storedPlans: Seq[StoredPlan],
    currentRoot: Group): Future[ScanDone] = {

    def appsInUse(roots: Seq[StoredGroup]): Map[PathId, SortedSet[OffsetDateTime]] = {
      val appVersionsInUse = new mutable.HashMap[PathId, mutable.Set[OffsetDateTime]] with mutable.MultiMap[PathId, OffsetDateTime] // scalastyle:off
      currentRoot.transitiveAppsById.foreach {
        case (id, app) =>
          appVersionsInUse.addBinding(id, app.version.toOffsetDateTime)
      }
      roots.foreach { root =>
        root.transitiveAppIds.foreach {
          case (id, version) =>
            appVersionsInUse.addBinding(id, version)
        }
      }
      appVersionsInUse.mapValues(_.to[SortedSet]).toMap
    }

    def rootsInUse(): Future[Seq[StoredGroup]] = {
      Future.sequence {
        storedPlans.flatMap(plan =>
          Seq(
            groupRepository.lazyRootVersion(plan.originalVersion),
            groupRepository.lazyRootVersion(plan.targetVersion))
        )
      }
    }.map(_.flatten)

    def appsExceedingMaxVersions(usedApps: Iterable[PathId]): Future[Map[PathId, SortedSet[OffsetDateTime]]] = {
      Future.sequence {
        usedApps.map { id =>
          appRepository.versions(id).runWith(Sink.sortedSet).map(id -> _)
        }
      }.map(_.filter(_._2.size > maxVersions).toMap)
    }

    async {
      val inUseRootFuture = rootsInUse()

      val allAppIdsFuture = appRepository.ids().runWith(Sink.set)
      val allAppIds = await(allAppIdsFuture)
      val inUseRoots = await(inUseRootFuture)
      val usedApps = appsInUse(inUseRoots)
      val appsWithTooManyVersions = await(appsExceedingMaxVersions(usedApps.keys))

      val appVersionsToDelete = appsWithTooManyVersions.map {
        case (id, versions) =>
          val candidateVersions = versions.diff(usedApps.getOrElse(id, SortedSet.empty))
          id -> candidateVersions.take(versions.size - maxVersions)
      }

      val appsToCompletelyDelete = allAppIds.diff(usedApps.keySet)
      ScanDone(appsToCompletelyDelete, appVersionsToDelete, rootsToDelete)
    }
  }
}

private trait CompactBehavior[K, C, S] { this: Actor with ActorLogging with ScanBehavior[K, C, S] =>
  val maxVersions: Int
  val appRepository: AppRepositoryImpl[K, C, S]
  val groupRepository: StoredGroupRepositoryImpl[K, C, S]
  val self: ActorRef

  def idle: Receive

  def compacting(
    appsDeleting: Set[PathId],
    appVersionsDeleting: Map[PathId, SortedSet[OffsetDateTime]],
    rootVersionsDeleting: Set[OffsetDateTime],
    promises: List[Promise[Done]], gcRequested: Boolean): Receive = {
    case RunGC =>
      context.become(compacting(appsDeleting, appVersionsDeleting, rootVersionsDeleting, promises, gcRequested))
    case CompactDone =>
      promises.foreach(_.success(Done))
      if (gcRequested) {
        context.become(scanning(
          Set.empty,
          new mutable.HashMap[PathId, mutable.Set[OffsetDateTime]] with mutable.MultiMap[PathId, OffsetDateTime],
          Set.empty, gcRequested = false))
      } else {
        context.become(idle)
      }
    case StoreApp(appId, Some(version), promise) =>
      if (appsDeleting.contains(appId) || appVersionsDeleting.get(appId).fold(false)(_.contains(version))) {
        context.become(compacting(
          appsDeleting,
          appVersionsDeleting,
          rootVersionsDeleting,
          promise :: promises,
          gcRequested))
      } else {
        promise.success(Done)
      }
    case StoreApp(appId, _, promise) =>
      if (appsDeleting.contains(appId)) {
        context.become(compacting(
          appsDeleting,
          appVersionsDeleting,
          rootVersionsDeleting,
          promise :: promises,
          gcRequested))
      } else {
        promise.success(Done)
      }
    case StoreRoot(root, promise) =>
      // the last case could be optimized to actually check the versions...
      if (rootVersionsDeleting.contains(root.version) ||
        appsDeleting.intersect(root.transitiveAppIds.keySet).nonEmpty ||
        appVersionsDeleting.keySet.intersect(root.transitiveAppIds.keySet).nonEmpty) {
        context.become(compacting(
          appsDeleting,
          appVersionsDeleting,
          rootVersionsDeleting,
          promise :: promises,
          gcRequested))
      } else {
        promise.success(Done)
      }
    case StorePlan(plan, promise) =>
      val promise1 = Promise[Done]()
      val promise2 = Promise[Done]()
      self ! StoreRoot(StoredGroup(plan.original), promise1)
      self ! StoreRoot(StoredGroup(plan.target), promise2)
      promise.completeWith(Future.sequence(Seq(promise1.future, promise2.future)).map(_ => Done))
  }

  def compact(appsToDelete: Set[PathId], appVersionsToDelete: Map[PathId, SortedSet[OffsetDateTime]],
    rootVersionsToDelete: Set[OffsetDateTime]): Future[CompactDone] = {
    async {
      if (rootVersionsToDelete.nonEmpty) {
        log.info(s"Deleting Root Versions ${rootVersionsToDelete.mkString(", ")} as there are currently " +
          s"${rootVersionsToDelete.size} which exceeds the maximum size $maxVersions and nothing refers to them anymore.")
      }
      if (appsToDelete.nonEmpty) {
        log.info(s"Deleting Applications: (${appsToDelete.mkString(", ")}) as no roots refer to them")
      }
      if (appVersionsToDelete.nonEmpty) {
        log.info("Deleting Application Versions " +
          s"(${appVersionsToDelete.mapValues(_.mkString("[", ", ", "]")).mkString(", ")}) as no roots refer to them" +
          " and they exceeded max versions")
      }
      val appFutures = appsToDelete.map(appRepository.delete)
      val appVersionFutures = appVersionsToDelete.flatMap {
        case (id, versions) =>
          versions.map { version => appRepository.deleteVersion(id, version) }
      }
      val rootFutures = rootVersionsToDelete.map(groupRepository.deleteRootVersion)
      await(Future.sequence(appFutures))
      await(Future.sequence(appVersionFutures))
      await(Future.sequence(rootFutures))
      CompactDone
    }.recover {
      case NonFatal(e) =>
        log.error("While deleting unused objects, encountered an error", e)
        CompactDone
    }
  }
}

object GcActor {
  sealed trait Message extends Product with Serializable
  case class ScanDone(appsToDelete: Set[PathId], appVersionsToDelete: Map[PathId, SortedSet[OffsetDateTime]],
    rootVersionsToDelete: Set[OffsetDateTime]) extends Message
  case object RunGC extends Message
  sealed trait CompactDone extends Message
  case object CompactDone extends CompactDone

  sealed trait StoreEntity extends Message {
    val promise: Promise[Done]
  }
  object StoreEntity {
    def unapply(se: StoreEntity): Option[Promise[Done]] = Some(se.promise)
  }
  case class StoreApp(appId: PathId, version: Option[OffsetDateTime], promise: Promise[Done]) extends StoreEntity
  case class StoreRoot(root: StoredGroup, promise: Promise[Done]) extends StoreEntity
  case class StorePlan(plan: DeploymentPlan, promise: Promise[Done]) extends StoreEntity
}
