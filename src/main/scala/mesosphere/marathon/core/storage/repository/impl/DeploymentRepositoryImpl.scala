package mesosphere.marathon.core.storage.repository.impl

import java.time.OffsetDateTime

import akka.actor.{ Actor, ActorRefFactory, Props }
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos
import mesosphere.marathon.core.storage.repository.impl.GcActor.{ DeleteDone, DeletedPlan }
import mesosphere.marathon.core.storage.repository.{ DeploymentRepository, GroupRepository }
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.state.{ Group, PathId, Timestamp }
import mesosphere.marathon.stream.Sink
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.async.Async.{ async, await }
import scala.collection.immutable.{ Seq, SortedSet }
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try
import scala.util.control.NonFatal

case class StoredPlan(
    id: String,
    originalVersion: OffsetDateTime,
    targetVersion: OffsetDateTime,
    version: OffsetDateTime) extends StrictLogging {
  def resolve(groupRepository: GroupRepository)(implicit ctx: ExecutionContext): Future[Option[DeploymentPlan]] =
    async {
      val originalFuture = groupRepository.rootVersion(originalVersion)
      val targetFuture = groupRepository.rootVersion(targetVersion)
      val (original, target) = (await(originalFuture), await(targetFuture))
      (original, target) match {
        case (Some(o), Some(t)) =>
          Some(DeploymentPlan(o, t, version = Timestamp(version), id = Some(id)))
        case (_, None) | (None, _) =>
          logger.error(s"While retrieving $id, either original ($original)"
            + s" or target ($target) were no longer available")
          throw new IllegalStateException("Missing target or original")
        case _ =>
          None
      }
    }

  def toProto: Protos.DeploymentPlanDefinition = {
    Protos.DeploymentPlanDefinition.newBuilder
      .setId(id)
      .setOriginalRootVersion(StoredPlan.DateFormat.format(originalVersion))
      .setTargetRootVersion(StoredPlan.DateFormat.format(targetVersion))
      .setTimestamp(StoredPlan.DateFormat.format(version))
      .build()
  }
}

object StoredPlan {
  val DateFormat = StoredGroup.DateFormat

  def apply(deploymentPlan: DeploymentPlan): StoredPlan = {
    StoredPlan(deploymentPlan.id, deploymentPlan.original.version.toOffsetDateTime,
      deploymentPlan.target.version.toOffsetDateTime, deploymentPlan.version.toOffsetDateTime)
  }

  def apply(proto: Protos.DeploymentPlanDefinition): StoredPlan = {
    val version = if (proto.hasTimestamp) {
      Try(OffsetDateTime.parse(proto.getTimestamp, DateFormat)).getOrElse(OffsetDateTime.MIN)
    } else {
      OffsetDateTime.MIN
    }
    StoredPlan(
      proto.getId,
      Try(OffsetDateTime.parse(proto.getOriginalRootVersion, DateFormat)).getOrElse(OffsetDateTime.MIN),
      Try(OffsetDateTime.parse(proto.getTargetRootVersion, DateFormat)).getOrElse(OffsetDateTime.MIN),
      version)
  }
}

// TODO: We should probably cache the plans we resolve...
// TODO: Add more docs, especially about GC.
class DeploymentRepositoryImpl[K, C, S](
    persistenceStore: PersistenceStore[K, C, S],
    groupRepository: StoredGroupRepositoryImpl[K, C, S],
    appRepository: AppRepositoryImpl[K, C, S],
    maxVersions: Int)(implicit
  ir: IdResolver[String, StoredPlan, C, K],
    marshaller: Marshaller[StoredPlan, S],
    unmarshaller: Unmarshaller[S, StoredPlan],
    ctx: ExecutionContext,
    actorRefFactory: ActorRefFactory,
    mat: Materializer) extends DeploymentRepository {

  private val gcActor = actorRefFactory.actorOf(
    Props(classOf[GcActor[K, C, S]], this, groupRepository,
      appRepository, maxVersions, mat, ctx), "PersistenceGarbageCollector")

  val repo = new PersistenceStoreRepository[String, StoredPlan, K, C, S](persistenceStore, _.id)

  override def store(v: DeploymentPlan): Future[Done] = repo.store(StoredPlan(v))

  override def delete(id: String): Future[Done] = async {
    val plan = await(get(id))
    val future = repo.delete(id)
    plan.foreach(p => future.onComplete(_ => gcActor ! GcActor.DeletedPlan(p)))
    await(future)
  }

  override def ids(): Source[String, NotUsed] = repo.ids()

  override def all(): Source[DeploymentPlan, NotUsed] =
    repo.ids().mapAsync(Int.MaxValue)(get).collect { case Some(g) => g }

  override def get(id: String): Future[Option[DeploymentPlan]] = async {
    await(repo.get(id)) match {
      case Some(storedPlan) =>
        await(storedPlan.resolve(groupRepository))
      case None =>
        None
    }
  }

  private[impl] def lazyAll(): Source[StoredPlan, NotUsed] =
    repo.ids().mapAsync(Int.MaxValue)(repo.get).collect { case Some(g) => g }
}

/**
  * Actor  that when a plan is deleted will:
  *
  * - start up a deletion, future deletes are coalesced into the next delete "round"
  * - Look at all the current deployment plans + all of their roots and the current root.
  * - Look at all of the root versions, if there are more than max versions, delete
  *   the oldest root that is not referred to by any other plan.
  * - If there are roots to delete, delete them, then go through all of the apps and app versions
  *   from _all of the roots currently referenced_ and find appVersions that are either
  *   (a) able to be _completely removed_
  *   (b) have too many versions
  * - Apps that are referenced by _no roots_ are deleted.
  * - Apps that have too many versions will be pruned by deleting the oldest versions
  *   that are not referenced by any roots.
  *
  *
  * @todo I believe we technically need transactions or we need to some how block
  *       apps/roots from being stored _while_ GC is taking place.
  */
private class GcActor[K, C, S](
  deploymentRepository: DeploymentRepositoryImpl[K, C, S],
  groupRepository: StoredGroupRepositoryImpl[K, C, S],
  appRepository: AppRepositoryImpl[K, C, S],
  maxVersions: Int)(implicit mat: Materializer, ctx: ExecutionContext)
    extends Actor with StrictLogging {
  override val receive: Receive = idle

  def idle: Receive = {
    case DeletedPlan(plan) =>
      delete()
      context.become(deleting(Nil))
  }

  def deleting(pending: List[DeploymentPlan]): Receive = {
    case DeletedPlan(plan) =>
      context.become(deleting(plan :: pending))
    case DeleteDone =>
      if (pending.nonEmpty) {
        delete()
        context.become(deleting(Nil))
      } else {
        context.become(idle)
      }
  }

  private def delete(): Unit = {
    val future = async {
      val rootVersions = await(groupRepository.rootVersions().runWith(Sink.sortedSet))
      if (rootVersions.size <= maxVersions) {
        Done
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
          Done
        } else {
          val rootsToDelete = deletionCandidates.take(rootVersions.size - maxVersions)
          if (rootsToDelete.isEmpty) {
            Done
          } else {
            logger.info(s"Deleting Root Versions ${rootsToDelete.mkString(", ")} as there are currently " +
              s"${rootVersions.size} which exceeds the maximum size $maxVersions and nothing refers to them anymore.")
            // intentionally delete the roots first so apps can't be referred to.
            await(Future.sequence(rootsToDelete.map(groupRepository.deleteRootVersion)))
            await(deleteUnusedApps(storedPlans, currentRoot))
          }
        }
      }
    }
    future.onFailure {
      case NonFatal(e) => logger.error("Failed to delete extra root versions", e)
    }
    future.onComplete(_ => self ! DeleteDone)
  }

  private def deleteUnusedApps(
    storedPlans: Seq[StoredPlan],
    currentRoot: Group): Future[Done] = {

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
      if (appsToCompletelyDelete.nonEmpty) {
        logger.info(s"Deleting Applications: (${appsToCompletelyDelete.mkString(", ")}) as no roots refer to them")
      }
      val deleteAppsFuture = Future.sequence(appsToCompletelyDelete.map(appRepository.delete))
      if (appVersionsToDelete.nonEmpty) {
        logger.info("Deleting Application Versions " +
          s"(${appVersionsToDelete.mapValues(_.mkString("[", ", ", "]")).mkString(", ")}) as no roots refer to them" +
          " and they exceeded max versions")
      }
      val deleteVersionsFuture = Future.sequence(appVersionsToDelete.flatMap {
        case (id, versions) =>
          versions.map { version => appRepository.deleteVersion(id, version) }
      })
      await(deleteVersionsFuture)
      await(deleteAppsFuture)
      Done
    }
  }
}

object GcActor {
  case class DeletedPlan(plans: DeploymentPlan)
  case object DeleteDone
}