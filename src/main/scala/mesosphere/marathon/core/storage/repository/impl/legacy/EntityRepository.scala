package mesosphere.marathon.core.storage.repository.impl.legacy

// scalastyle:off
import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.event.EventSubscribers
import mesosphere.marathon.core.storage.repository.{ AppRepository, DeploymentRepository, EventSubscribersRepository, FrameworkIdRepository, Repository, TaskFailureRepository, TaskRepository, VersionedRepository }
import mesosphere.marathon.core.storage.repository.impl.legacy.store.EntityStore
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, MarathonState, MarathonTaskState, PathId, StateMetrics, TaskFailure, Timestamp, VersionedEntry }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.CallerThreadExecutionContext
import mesosphere.util.state.FrameworkId

import scala.async.Async.{ async, await }
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
// scalastyle:on

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

  def all(): Source[T, NotUsed] = {
    val future = async {
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
        Future.sequence(currentDeleteResult +: versionsDeleteResult.toVector).map(_ => Done)
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

  override def store(v: T): Future[Done] = timedWrite {
    async {
      val unversionedId = idToString(valueId(v))
      await(store.store(unversionedId, v))
      await(store.store(versionKey(unversionedId, v.version), v))
      await(limitNumberOfVersions(unversionedId))
      Done
    }
  }

  def storeVersion(v: T): Future[Done] = timedWrite {
    async {
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

class DeploymentEntityRepository(private[storage] val store: EntityStore[DeploymentPlan])(implicit
  ctx: ExecutionContext = ExecutionContext.global,
  metrics: Metrics)
    extends LegacyEntityRepository[String, DeploymentPlan](store, identity, identity, _.id) with DeploymentRepository

class TaskEntityRepository(private[storage] val store: EntityStore[MarathonTaskState])(implicit
  ctx: ExecutionContext = ExecutionContext.global,
  metrics: Metrics)
    extends TaskRepository with VersionedEntry {
  private[storage] val repo = new LegacyEntityRepository[Task.Id, MarathonTaskState](
    store,
    _.idString, Task.Id(_), task => Task.Id(task.task.getId))
  override def ids(): Source[Task.Id, NotUsed] = repo.ids()

  override def all(): Source[Task, NotUsed] = repo.all().map(t => TaskSerializer.fromProto(t.toProto))

  override def get(id: Task.Id): Future[Option[Task]] =
    repo.get(id).map(_.map(t => TaskSerializer.fromProto(t.toProto)))

  override def delete(id: Task.Id): Future[Done] = repo.delete(id)

  override def store(v: Task): Future[Done] = repo.store(MarathonTaskState(TaskSerializer.toProto(v)))
}

object TaskEntityRepository {
  val storePrefix = "task:"
}

class TaskFailureEntityRepository(store: EntityStore[TaskFailure], maxVersions: Int)(implicit
  ctx: ExecutionContext = ExecutionContext.global,
  metrics: Metrics)
    extends LegacyVersionedRepository[PathId, TaskFailure](store, maxVersions, _.safePath, PathId.fromSafePath, _.appId)
    with TaskFailureRepository

class FrameworkIdEntityRepository(store: EntityStore[FrameworkId])(implicit
  ctx: ExecutionContext = ExecutionContext.global,
  metrics: Metrics)
    extends FrameworkIdRepository {
  private val id = "id"

  override def get(): Future[Option[FrameworkId]] = store.fetch(id)

  override def store(v: FrameworkId): Future[Done] =
    store.modify(id) { _ => v }.map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)

  override def delete(): Future[Done] =
    store.expunge(id).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)
}

class EventSubscribersEntityRepository(store: EntityStore[EventSubscribers])(implicit
  ctx: ExecutionContext = ExecutionContext.global,
    metrics: Metrics) extends EventSubscribersRepository {
  private val id = "http_event_subscribers"

  override def get(): Future[Option[EventSubscribers]] = store.fetch(id)

  override def store(v: EventSubscribers): Future[Done] =
    store.modify(id) { _ => v }.map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)

  override def delete(): Future[Done] =
    store.expunge(id).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)
}
