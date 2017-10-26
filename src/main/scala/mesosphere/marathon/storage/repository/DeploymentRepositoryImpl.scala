package mesosphere.marathon
package storage.repository

import java.time.OffsetDateTime

import akka.actor.ActorRefFactory
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.storage.repository.RepositoryConstants
import mesosphere.marathon.core.storage.repository.impl.PersistenceStoreRepository
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.state.{ RootGroup, Timestamp }
import mesosphere.marathon.storage.repository.GcActor.{ StoreApp, StorePlan, StorePod, StoreRoot }

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future, Promise }

case class StoredPlan(
    id: String,
    originalVersion: OffsetDateTime,
    targetVersion: OffsetDateTime,
    version: OffsetDateTime) extends StrictLogging {
  @SuppressWarnings(Array("all")) // async/await
  def resolve(groupRepository: GroupRepository)(implicit ctx: ExecutionContext): Future[Option[DeploymentPlan]] =
    async { // linter:ignore UnnecessaryElseBranch
      val originalFuture = groupRepository.rootVersion(originalVersion)
      val targetFuture = groupRepository.rootVersion(targetVersion)
      val (original, target) = (await(originalFuture), await(targetFuture))
      (original, target) match {
        case (Some(o), Some(t)) =>
          Some(DeploymentPlan(RootGroup.fromGroup(o), RootGroup.fromGroup(t), version = Timestamp(version), id = Some(id)))
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
      OffsetDateTime.parse(proto.getTimestamp, DateFormat)
    } else {
      OffsetDateTime.MIN
    }
    StoredPlan(
      proto.getId,
      OffsetDateTime.parse(proto.getOriginalRootVersion, DateFormat),
      OffsetDateTime.parse(proto.getTargetRootVersion, DateFormat),
      version)
  }
}

// TODO: We should probably cache the plans we resolve...
class DeploymentRepositoryImpl[K, C, S](
    persistenceStore: PersistenceStore[K, C, S],
    groupRepository: StoredGroupRepositoryImpl[K, C, S],
    appRepository: AppRepositoryImpl[K, C, S],
    podRepository: PodRepositoryImpl[K, C, S],
    maxVersions: Int)(implicit
  ir: IdResolver[String, StoredPlan, C, K],
    marshaller: Marshaller[StoredPlan, S],
    unmarshaller: Unmarshaller[S, StoredPlan],
    ctx: ExecutionContext,
    actorRefFactory: ActorRefFactory,
    mat: Materializer) extends DeploymentRepository {

  private val gcActor = GcActor(
    s"PersistenceGarbageCollector-$hashCode",
    this, groupRepository, appRepository, podRepository, maxVersions)

  appRepository.beforeStore = Some((id, version) => {
    val promise = Promise[Done]()
    gcActor ! StoreApp(id, version, promise)
    promise.future
  })

  groupRepository.beforeStore = Some(group => {
    val promise = Promise[Done]()
    gcActor ! StoreRoot(group, promise)
    promise.future
  })

  podRepository.beforeStore = Some((id, version) => {
    val promise = Promise[Done]()
    gcActor ! StorePod(id, version, promise)
    promise.future
  })

  private def beforeStore(plan: DeploymentPlan): Future[Done] = {
    val promise = Promise[Done]()
    gcActor ! StorePlan(plan, promise)
    promise.future
  }

  val repo = new PersistenceStoreRepository[String, StoredPlan, K, C, S](persistenceStore, _.id)

  @SuppressWarnings(Array("all")) // async/await
  override def store(v: DeploymentPlan): Future[Done] = async { // linter:ignore UnnecessaryElseBranch
    await(beforeStore(v))
    await(repo.store(StoredPlan(v)))
  }

  @SuppressWarnings(Array("all")) // async/await
  override def delete(id: String): Future[Done] = async { // linter:ignore UnnecessaryElseBranch
    val plan = await(get(id))
    val future = repo.delete(id)
    plan.foreach(p => future.onComplete(_ => gcActor ! GcActor.RunGC))
    await(future)
  }

  override def ids(): Source[String, NotUsed] = repo.ids()

  override def all(): Source[DeploymentPlan, NotUsed] =
    repo.ids().mapAsync(RepositoryConstants.maxConcurrency)(get).collect { case Some(g) => g }

  @SuppressWarnings(Array("all")) // async/await
  override def get(id: String): Future[Option[DeploymentPlan]] = async { // linter:ignore UnnecessaryElseBranch
    await(repo.get(id)) match {
      case Some(storedPlan) =>
        await(storedPlan.resolve(groupRepository))
      case None =>
        None
    }
  }

  private[storage] def lazyAll(): Source[StoredPlan, NotUsed] =
    repo.ids().mapAsync(RepositoryConstants.maxConcurrency)(repo.get).collect { case Some(g) => g }
}

