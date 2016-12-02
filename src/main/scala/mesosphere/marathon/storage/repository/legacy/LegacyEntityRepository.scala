package mesosphere.marathon.storage.repository.legacy

import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.event.EventSubscribers
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.repository.{ RawRepository, Repository, VersionedRepository }
import mesosphere.marathon.storage.repository.legacy.store.EntityStore
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.{ AppRepository, DeploymentRepository, EventSubscribersRepository, FrameworkIdRepository, GroupRepository, InstanceRepository, PodRepository, TaskFailureRepository, TaskRepository }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.CallerThreadExecutionContext
import mesosphere.util.state.FrameworkId

import scala.async.Async.{ async, await }
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

private[storage] class LegacyEntityRepository[Id, T <: MarathonState[_, T]](
    store: EntityStore[T],
    idToString: (Id) => String,
    stringToId: (String) => Id,
    valueId: (T) => Id)(implicit
  ctx: ExecutionContext,
    val metrics: Metrics) extends StateMetrics with Repository[Id, T] {
  import VersionedEntry.noVersionKey

  def ids(): Source[Id, NotUsed] = {
    val idFuture = store.names().map(_.collect {
      case name: String if noVersionKey(name) => name
    })
    Source.fromFuture(idFuture).mapConcat(identity).map(stringToId)
  }

  @SuppressWarnings(Array("all")) // async/await
  def all(): Source[T, NotUsed] = {
    val future = async { // linter:ignore UnnecessaryElseBranch
      val names = await {
        store.names().map(_.collect {
          case name: String if noVersionKey(name) => name
        })
      }
      val all = names.map(store.fetch)
      await(Future.sequence(all))
    }
    Source.fromFuture(future).mapConcat(identity).collect { case Some(t) => t }
  }

  def get(id: Id): Future[Option[T]] = timedRead(store.fetch(idToString(id)))

  def delete(id: Id): Future[Done] =
    store.expunge(idToString(id)).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)

  def store(value: T): Future[Done] =
    timedWrite {
      store.store(idToString(valueId(value)), value)
        .map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)
    }
}

private[storage] class LegacyVersionedRepository[Id, T <: MarathonState[_, T]](
  store: EntityStore[T],
  maxVersions: Int,
  idToString: (Id) => String,
  stringToId: (String) => Id,
  valueId: (T) => Id)(implicit
  ctx: ExecutionContext,
  metrics: Metrics)
    extends LegacyEntityRepository[Id, T](store, idToString, stringToId, valueId) with VersionedRepository[Id, T] {

  import VersionedEntry._

  private def listVersions(id: String): Future[Seq[Timestamp]] = timedRead {
    val prefix = versionKeyPrefix(id)
    store.names().map(_.collect {
      case name: String if name.startsWith(prefix) =>
        Timestamp(name.substring(prefix.length))
    }.sorted.reverse)
  }

  def versions(id: Id): Source[OffsetDateTime, NotUsed] = {
    Source.fromFuture(listVersions(idToString(id))).mapConcat(identity).map(_.toOffsetDateTime)
  }

  def getVersion(id: Id, version: OffsetDateTime): Future[Option[T]] = {
    store.fetch(versionKey(idToString(id), Timestamp(version)))
  }

  override def delete(id: Id): Future[Done] = {
    timedWrite {
      val idString = idToString(id)
      listVersions(idString).flatMap { timestamps =>
        val versionsDeleteResult = timestamps.map { timestamp =>
          store.expunge(versionKey(idString, timestamp))
        }
        val currentDeleteResult = store.expunge(idString)
        Future.sequence(currentDeleteResult +: versionsDeleteResult.toIndexedSeq).map(_ => Done)
      }
    }
  }

  override def deleteCurrent(id: Id): Future[Done] = {
    timedWrite {
      val idString = idToString(id)
      if (noVersionKey(idString)) {
        store.expunge(idString).map(_ => Done)
      } else {
        Future.failed(new IllegalArgumentException(s"$idString is a versioned id."))
      }
    }
  }

  private[this] def limitNumberOfVersions(id: String): Future[Done] = {
    listVersions(id).flatMap { versions =>
      Future.sequence(versions.drop(maxVersions).map(version => store.expunge(versionKey(id, version))))
    }.map(_ => Done)
  }

  @SuppressWarnings(Array("all")) // async/await
  override def store(v: T): Future[Done] = timedWrite {
    async { // linter:ignore UnnecessaryElseBranch
      val unversionedId = idToString(valueId(v))
      await(store.store(unversionedId, v))
      await(store.store(versionKey(unversionedId, v.version), v))
      await(limitNumberOfVersions(unversionedId))
      Done
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  def storeVersion(v: T): Future[Done] = timedWrite {
    async { // linter:ignore UnnecessaryElseBranch
      val unversionedId = idToString(valueId(v))
      await(store.store(versionKey(unversionedId, v.version), v))
      await(limitNumberOfVersions(unversionedId))
      Done
    }
  }
}

class AppEntityRepository(
  store: EntityStore[AppDefinition],
  maxVersions: Int)(implicit
  ctx: ExecutionContext = ExecutionContext.global,
  metrics: Metrics) extends LegacyVersionedRepository[PathId, AppDefinition](
  store,
  maxVersions,
  _.safePath,
  PathId.fromSafePath, _.id) with AppRepository

class PodEntityRepository(
  store: EntityStore[PodDefinition],
  maxVersions: Int
)(implicit ctx: ExecutionContext = ExecutionContext.global, metrics: Metrics)
    extends LegacyVersionedRepository[PathId, PodDefinition](
      store,
      maxVersions,
      _.safePath,
      PathId.fromSafePath,
      _.id
    ) with PodRepository

class DeploymentEntityRepository(private[storage] val store: EntityStore[DeploymentPlan])(implicit
  ctx: ExecutionContext = ExecutionContext.global,
  metrics: Metrics)
    extends LegacyEntityRepository[String, DeploymentPlan](store, identity, identity, _.id) with DeploymentRepository

class TaskEntityRepository(private[storage] val store: EntityStore[MarathonTaskState])(implicit
  ctx: ExecutionContext = ExecutionContext.global,
  metrics: Metrics)
    extends TaskRepository with VersionedEntry with RawRepository[Task.Id, MarathonTask] {
  private[storage] val repo = new LegacyEntityRepository[Task.Id, MarathonTaskState](
    store,
    _.idString, Task.Id(_), task => Task.Id(task.task.getId))
  override def ids(): Source[Task.Id, NotUsed] = repo.ids()

  override def all(): Source[Task, NotUsed] = repo.all().map(t => TaskSerializer.fromProto(t.toProto))

  override def get(id: Task.Id): Future[Option[Task]] =
    repo.get(id).map(_.map(t => TaskSerializer.fromProto(t.toProto)))

  override def getRaw(id: Task.Id): Future[Option[MarathonTask]] = repo.get(id).map(_.map(_.task))

  override def allRaw(): Source[MarathonTask, NotUsed] = repo.all().map(_.task)

  override def storeRaw(task: MarathonTask): Future[Done] = repo.store(MarathonTaskState(task))

  override def delete(id: Task.Id): Future[Done] = repo.delete(id)

  override def store(v: Task): Future[Done] = repo.store(MarathonTaskState(TaskSerializer.toProto(v)))
}

object TaskEntityRepository {
  val storePrefix = "task:"
}

class InstanceEntityRepository(val store: EntityStore[Instance])(implicit
  ctx: ExecutionContext = ExecutionContext.global,
  metrics: Metrics)
    extends LegacyEntityRepository[Instance.Id, Instance](store, _.idString, Instance.Id.apply, _.instanceId)
    with InstanceRepository

class TaskFailureEntityRepository(store: EntityStore[TaskFailure], maxVersions: Int)(implicit
  ctx: ExecutionContext = ExecutionContext.global,
  metrics: Metrics)
    extends LegacyVersionedRepository[PathId, TaskFailure](store, maxVersions, _.safePath, PathId.fromSafePath, _.appId)
    with TaskFailureRepository

class FrameworkIdEntityRepository(store: EntityStore[FrameworkId])
    extends FrameworkIdRepository {
  private val id = "id"

  override def get(): Future[Option[FrameworkId]] = store.fetch(id)

  override def store(v: FrameworkId): Future[Done] =
    store.modify(id) { _ => v }.map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)

  override def delete(): Future[Done] =
    store.expunge(id).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)
}

class EventSubscribersEntityRepository(store: EntityStore[EventSubscribers]) extends EventSubscribersRepository {
  private val id = "http_event_subscribers"

  override def get(): Future[Option[EventSubscribers]] = store.fetch(id)

  override def store(v: EventSubscribers): Future[Done] =
    store.modify(id) { _ => v }.map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)

  override def delete(): Future[Done] =
    store.expunge(id).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)
}

class GroupEntityRepository(
  private[storage] val store: EntityStore[Group],
  maxVersions: Int,
  appRepository: AppRepository,
  podRepository: PodRepository)(implicit
  ctx: ExecutionContext = ExecutionContext.global,
  metrics: Metrics)
    extends LegacyVersionedRepository[PathId, Group](
      store,
      maxVersions, _ => "root", _ => PathId("/"), _ => PathId("/")) with GroupRepository {
  import GroupEntityRepository._

  override def root(): Future[RootGroup] = timedRead {
    get(ZkRootName).map {
      group => RootGroup.fromGroup(group.getOrElse(Group.empty(PathId.empty)))
    }(CallerThreadExecutionContext.callerThreadExecutionContext)
  }

  override def rootVersions(): Source[OffsetDateTime, NotUsed] =
    versions(ZkRootName)

  override def rootVersion(version: OffsetDateTime): Future[Option[RootGroup]] =
    getVersion(ZkRootName, version).map(_.map(RootGroup.fromGroup))

  @SuppressWarnings(Array("all")) // async/await
  override def storeRoot(rootGroup: RootGroup, updatedApps: Seq[AppDefinition], deletedApps: Seq[PathId],
    updatedPods: Seq[PodDefinition], deletedPods: Seq[PathId]): Future[Done] = {
    // because the groups store their apps, we can just delete unused apps.
    async { // linter:ignore UnnecessaryElseBranch
      val storeAppsFutures = updatedApps.map(appRepository.store)
      val storePodsFuture = updatedPods.map(podRepository.store)
      val deleteAppFutures = deletedApps.map(appRepository.delete)
      val deletePodsFuture = deletedPods.map(podRepository.delete)
      await(Future.sequence(storeAppsFutures))
      await(Future.sequence(storePodsFuture))
      await(Future.sequence(deleteAppFutures).recover { case NonFatal(e) => Done })
      await(Future.sequence(deletePodsFuture).recover { case NonFatal(e) => Done })
      await(store(rootGroup))
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def storeRootVersion(rootGroup: RootGroup, updatedApps: Seq[AppDefinition], updatedPods: Seq[PodDefinition]): Future[Done] = {
    async { // linter:ignore UnnecessaryElseBranch
      val storeAppsFutures = updatedApps.map(appRepository.store)
      val storePodsFutures = updatedPods.map(podRepository.store)
      await(Future.sequence(Seq(storeAppsFutures, storePodsFutures).flatten))
      await(storeVersion(rootGroup))
    }
  }
}

object GroupEntityRepository {
  val ZkRootName = PathId("/")
}
