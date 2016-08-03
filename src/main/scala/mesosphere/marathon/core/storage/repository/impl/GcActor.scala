package mesosphere.marathon.core.storage.repository.impl

// scalastyle:off
import java.time.{ Duration, Instant, OffsetDateTime }

import akka.Done
import akka.actor.{ ActorLogging, ActorRef, ActorRefFactory, FSM, LoggingFSM, Props }
import akka.pattern._
import akka.stream.Materializer
import mesosphere.marathon.core.storage.repository.impl.GcActor._
import mesosphere.marathon.metrics.Metrics
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
private[storage] class GcActor[K, C, S](
  val deploymentRepository: DeploymentRepositoryImpl[K, C, S],
  val groupRepository: StoredGroupRepositoryImpl[K, C, S],
  val appRepository: AppRepositoryImpl[K, C, S],
  val maxVersions: Int)(implicit val mat: Materializer, val ctx: ExecutionContext, metrics: Metrics)
    extends FSM[State, Data] with LoggingFSM[State, Data] with ScanBehavior[K, C, S] with CompactBehavior[K, C, S] {

  private var totalGcs = metrics.counter("GarbageCollector.totalGcs")
  private var lastScanStart = Instant.now()
  private var scanTime = metrics.histogram("GarbageCollector.scanTime")
  private var lastCompactStart = Instant.now()
  private var compactTime = metrics.histogram("GarbageCollector.compactTime")

  startWith(Idle, IdleData)

  when(Idle) {
    case Event(RunGC, _) =>
      scan().pipeTo(self)
      goto(Scanning) using UpdatedEntities()
    case Event(StoreEntity(promise), _) =>
      promise.success(Done)
      stay
    case Event(_: Message, _) =>
      stay
    // ignore
  }

  onTransition {
    case Idle -> Scanning =>
      lastScanStart = Instant.now()
    case Scanning -> Compacting =>
      lastCompactStart = Instant.now()
      val scanDuration = Duration.between(lastScanStart, lastCompactStart)
      log.info(s"Completed scan phase in $scanDuration")
      scanTime.update(scanDuration.toMillis)
    case Scanning -> Idle =>
      val scanDuration = Duration.between(lastScanStart, Instant.now)
      log.info(s"Completed empty scan in $scanDuration")
      scanTime.update(scanDuration.toMillis)
    case Compacting -> Idle =>
      val compactDuration = Duration.between(lastCompactStart, Instant.now)
      log.info(s"Completed compaction in $compactDuration")
      compactTime.update(compactDuration.toMillis)
      totalGcs.inc()
    case Compacting -> Scanning =>
      lastScanStart = Instant.now()
      val compactDuration = Duration.between(lastCompactStart, Instant.now)
      log.info(s"Completed compaction in $compactDuration")
      compactTime.update(compactDuration.toMillis)
      totalGcs.inc()
  }

  initialize()
}

private[storage] trait ScanBehavior[K, C, S] { this: FSM[State, Data] with ActorLogging with CompactBehavior[K, C, S] =>
  implicit val mat: Materializer
  implicit val ctx: ExecutionContext
  val maxVersions: Int
  val appRepository: AppRepositoryImpl[K, C, S]
  val groupRepository: StoredGroupRepositoryImpl[K, C, S]
  val deploymentRepository: DeploymentRepositoryImpl[K, C, S]
  val self: ActorRef

  when(Scanning) {
    case Event(RunGC, updates: UpdatedEntities) =>
      stay using updates.copy(gcRequested = true)
    case Event(done: ScanDone, updates: UpdatedEntities) =>
      if (done.isEmpty) {
        if (updates.gcRequested) {
          scan().pipeTo(self)
          goto(Scanning) using UpdatedEntities()
        } else {
          goto(Idle) using IdleData
        }
      } else {
        val (appsToDelete, appVersionsToDelete, rootsToDelete) =
          computeActualDeletions(updates.appsStored, updates.appVersionsStored, updates.rootsStored, done)
        compact(appsToDelete, appVersionsToDelete, rootsToDelete).pipeTo(self)
        goto(Compacting) using
          BlockedEntities(appsToDelete, appVersionsToDelete, rootsToDelete, Nil, updates.gcRequested)
      }
    case Event(StoreApp(appId, Some(version), promise), updates: UpdatedEntities) =>
      promise.success(Done)
      val appVersions = updates.appVersionsStored + (appId -> (updates.appVersionsStored(appId) + version))
      stay using updates.copy(appVersionsStored = appVersions)
    case Event(StoreApp(appId, _, promise), updates: UpdatedEntities) =>
      promise.success(Done)
      stay using updates.copy(appsStored = updates.appsStored + appId)
    case Event(StoreRoot(root, promise), updates: UpdatedEntities) =>
      promise.success(Done)
      val appVersions = addAppVersions(root.transitiveAppIds, updates.appVersionsStored)
      stay using updates.copy(rootsStored = updates.rootsStored + root.version, appVersionsStored = appVersions)
    case Event(StorePlan(plan, promise), updates: UpdatedEntities) =>
      promise.success(Done)
      val originalUpdates =
        addAppVersions(
          plan.original.transitiveAppsById.mapValues(_.version.toOffsetDateTime),
          updates.appVersionsStored)
      val allUpdates =
        addAppVersions(plan.target.transitiveAppsById.mapValues(_.version.toOffsetDateTime), originalUpdates)
      val newRootsStored = updates.rootsStored ++
        Set(plan.original.version.toOffsetDateTime, plan.target.version.toOffsetDateTime)
      stay using updates.copy(appVersionsStored = allUpdates, rootsStored = newRootsStored)
    case Event(_: Message, _) =>
      stay
  }

  def computeActualDeletions(
    appsStored: Set[PathId],
    appVersionsStored: Map[PathId, Set[OffsetDateTime]], // scalastyle:off
    rootsStored: Set[OffsetDateTime],
    scanDone: ScanDone): (Set[PathId], Map[PathId, Set[OffsetDateTime]], Set[OffsetDateTime]) = {
    val ScanDone(appsToDelete, appVersionsToDelete, rootVersionsToDelete) = scanDone
    val appsToActuallyDelete = appsToDelete.diff(appsStored.union(appVersionsStored.keySet))
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
    appVersionsStored: Map[PathId, Set[OffsetDateTime]]): Map[PathId, Set[OffsetDateTime]] = {
    apps.foldLeft(appVersionsStored) {
      case (appVersions, (pathId, version)) =>
        appVersions + (pathId -> (appVersions(pathId) + version))
    }
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

        val currentlyInDeployment: SortedSet[OffsetDateTime] = storedPlans.flatMap { plan =>
          Seq(plan.originalVersion, plan.targetVersion)
        }(collection.breakOut)

        val deletionCandidates = rootVersions.diff(currentlyInDeployment + currentRoot.version.toOffsetDateTime)

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
    }.recover {
      case NonFatal(e) =>
        log.error("Error while scanning for unused roots {}: {}", e, Option(e.getMessage).getOrElse(""))
        ScanDone()
    }
  }

  private def scanUnusedApps(
    rootsToDelete: Set[OffsetDateTime],
    storedPlans: Seq[StoredPlan],
    currentRoot: Group): Future[ScanDone] = {

    def appsInUse(roots: Seq[StoredGroup]): Map[PathId, Set[OffsetDateTime]] = {
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
      appVersionsInUse.mapValues(_.to[Set]).toMap
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

    def appsExceedingMaxVersions(usedApps: Iterable[PathId]): Future[Map[PathId, Set[OffsetDateTime]]] = {
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
    }.recover {
      case NonFatal(e) =>
        log.error("Error while scanning for unused apps {}: {}", e, Option(e.getMessage).getOrElse(""))
        ScanDone()
    }
  }
}

private[storage] trait CompactBehavior[K, C, S] { this: FSM[State, Data] with ActorLogging with ScanBehavior[K, C, S] =>
  val maxVersions: Int
  val appRepository: AppRepositoryImpl[K, C, S]
  val groupRepository: StoredGroupRepositoryImpl[K, C, S]
  val self: ActorRef

  when(Compacting) {
    case Event(RunGC, blocked: BlockedEntities) =>
      stay using blocked.copy(gcRequested = true)
    case Event(CompactDone, blocked: BlockedEntities) =>
      blocked.promises.foreach(_.success(Done))
      if (blocked.gcRequested) {
        scan().pipeTo(self)
        goto(Scanning) using UpdatedEntities()
      } else {
        goto(Idle) using IdleData
      }
    case Event(StoreApp(appId, Some(version), promise), blocked: BlockedEntities) =>
      if (blocked.appsDeleting.contains(appId) ||
        blocked.appVersionsDeleting.get(appId).fold(false)(_.contains(version))) {
        stay using blocked.copy(promises = promise :: blocked.promises)
      } else {
        promise.success(Done)
        stay
      }
    case Event(StoreApp(appId, _, promise), blocked: BlockedEntities) =>
      if (blocked.appsDeleting.contains(appId)) {
        stay using blocked.copy(promises = promise :: blocked.promises)
      } else {
        promise.success(Done)
        stay
      }
    case Event(StoreRoot(root, promise), blocked: BlockedEntities) =>
      // the last case could be optimized to actually check the versions...
      if (blocked.rootsDeleting.contains(root.version) ||
        blocked.appsDeleting.intersect(root.transitiveAppIds.keySet).nonEmpty ||
        blocked.appVersionsDeleting.keySet.intersect(root.transitiveAppIds.keySet).nonEmpty) {
        stay using blocked.copy(promises = promise :: blocked.promises)
      } else {
        promise.success(Done)
        stay
      }
    case Event(StorePlan(plan, promise), blocked: BlockedEntities) =>
      val promise1 = Promise[Done]()
      val promise2 = Promise[Done]()
      self ! StoreRoot(StoredGroup(plan.original), promise1)
      self ! StoreRoot(StoredGroup(plan.target), promise2)
      promise.completeWith(Future.sequence(Seq(promise1.future, promise2.future)).map(_ => Done))
      stay
  }

  def compact(appsToDelete: Set[PathId], appVersionsToDelete: Map[PathId, Set[OffsetDateTime]],
    rootVersionsToDelete: Set[OffsetDateTime]): Future[CompactDone] = {
    async {
      if (rootVersionsToDelete.nonEmpty) {
        log.info(s"Deleting Root Versions ${rootVersionsToDelete.mkString(", ")} as nothing refers to them anymore.")
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
        log.error("While deleting unused objects, encountered an error {}: {}", e, Option(e.getMessage).getOrElse(""))
        CompactDone
    }
  }
}

object GcActor {
  private[storage] sealed trait State extends Product with Serializable
  case object Idle extends State
  case object Scanning extends State
  case object Compacting extends State

  private[storage] sealed trait Data extends Product with Serializable
  case object IdleData extends Data
  case class UpdatedEntities(
    appsStored: Set[PathId] = Set.empty,
    appVersionsStored: Map[PathId, Set[OffsetDateTime]] = Map.empty.withDefaultValue(Set.empty),
    rootsStored: Set[OffsetDateTime] = Set.empty,
    gcRequested: Boolean = false) extends Data
  case class BlockedEntities(
    appsDeleting: Set[PathId] = Set.empty,
    appVersionsDeleting: Map[PathId, Set[OffsetDateTime]] = Map.empty.withDefaultValue(Set.empty),
    rootsDeleting: Set[OffsetDateTime] = Set.empty,
    promises: List[Promise[Done]] = List.empty,
    gcRequested: Boolean = false) extends Data

  def props[K, C, S](
    deploymentRepository: DeploymentRepositoryImpl[K, C, S],
    groupRepository: StoredGroupRepositoryImpl[K, C, S],
    appRepository: AppRepositoryImpl[K, C, S],
    maxVersions: Int)(implicit mat: Materializer, ctx: ExecutionContext, metrics: Metrics): Props = {
    Props(new GcActor[K, C, S](deploymentRepository, groupRepository, appRepository, maxVersions))
  }

  def apply[K, C, S](
    name: String,
    deploymentRepository: DeploymentRepositoryImpl[K, C, S],
    groupRepository: StoredGroupRepositoryImpl[K, C, S],
    appRepository: AppRepositoryImpl[K, C, S],
    maxVersions: Int)(implicit
    mat: Materializer,
    ctx: ExecutionContext,
    actorRefFactory: ActorRefFactory, metrics: Metrics): ActorRef = {
    actorRefFactory.actorOf(props(deploymentRepository, groupRepository, appRepository, maxVersions), name)
  }

  sealed trait Message extends Product with Serializable
  case class ScanDone(
      appsToDelete: Set[PathId] = Set.empty,
      appVersionsToDelete: Map[PathId, Set[OffsetDateTime]] = Map.empty,
      rootVersionsToDelete: Set[OffsetDateTime] = Set.empty) extends Message {
    def isEmpty = appsToDelete.isEmpty && appVersionsToDelete.isEmpty && rootVersionsToDelete.isEmpty
  }
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
