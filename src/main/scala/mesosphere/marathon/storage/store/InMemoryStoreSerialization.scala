package mesosphere.marathon
package storage.store

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import mesosphere.marathon.core.event.EventSubscribers
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.store.IdResolver
import mesosphere.marathon.core.storage.store.impl.memory.{ Identity, RamId }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, PathId, TaskFailure }
import mesosphere.marathon.storage.repository.{ StoredGroup, StoredPlan }
import mesosphere.util.state.FrameworkId

trait InMemoryStoreSerialization {
  implicit def marshaller[V]: Marshaller[V, Identity] = Marshaller.opaque { a: V => Identity(a) }

  @SuppressWarnings(Array("AsInstanceOf"))
  implicit def unmarshaller[V]: Unmarshaller[Identity, V] =
    Unmarshaller.strict { a: Identity => a.value.asInstanceOf[V] }

  class InMemPathIdResolver[T](
    val category: String,
    val hasVersions: Boolean,
    getVersion: T => OffsetDateTime)
      extends IdResolver[PathId, T, String, RamId] {
    override def toStorageId(id: PathId, version: Option[OffsetDateTime]): RamId =
      RamId(category, id.path.mkString("_"), version)

    override def fromStorageId(key: RamId): PathId = PathId(key.id.split("_").toList, absolute = true)

    override def version(v: T): OffsetDateTime = getVersion(v)
  }

  implicit def appDefResolver: IdResolver[PathId, AppDefinition, String, RamId] =
    new InMemPathIdResolver[AppDefinition]("app", true, _.version.toOffsetDateTime)

  implicit val podDefResolver: IdResolver[PathId, PodDefinition, String, RamId] =
    new InMemPathIdResolver[PodDefinition]("pod", true, _.version.toOffsetDateTime)

  implicit val instanceResolver: IdResolver[Instance.Id, Instance, String, RamId] =
    new IdResolver[Instance.Id, Instance, String, RamId] {
      override def toStorageId(id: Id, version: Option[OffsetDateTime]): RamId =
        RamId(category, id.idString, version)
      override val category: String = "instance"
      override def fromStorageId(key: RamId): Id = Instance.Id(key.id)
      override val hasVersions: Boolean = false
      override def version(v: Instance): OffsetDateTime = OffsetDateTime.MIN
    }

  implicit val taskResolver: IdResolver[Task.Id, Task, String, RamId] =
    new IdResolver[Task.Id, Task, String, RamId] {
      override def toStorageId(id: Task.Id, version: Option[OffsetDateTime]): RamId =
        RamId(category, id.idString, version)
      override val category: String = "task"
      override def fromStorageId(key: RamId): Task.Id = Task.Id(key.id)
      override val hasVersions = false
      override def version(v: Task): OffsetDateTime = OffsetDateTime.MIN
    }

  implicit val deploymentResolver: IdResolver[String, StoredPlan, String, RamId] =
    new IdResolver[String, StoredPlan, String, RamId] {
      override def toStorageId(id: String, version: Option[OffsetDateTime]): RamId =
        RamId(category, id, version)
      override val category: String = "deployment"
      override def fromStorageId(key: RamId): String = key.id
      override val hasVersions = false
      override def version(v: StoredPlan): OffsetDateTime = OffsetDateTime.MIN
    }

  implicit def taskFailureResolver: IdResolver[PathId, TaskFailure, String, RamId] =
    new InMemPathIdResolver[TaskFailure]("taskfailure", true, _.version.toOffsetDateTime)

  implicit def groupResolver: IdResolver[PathId, StoredGroup, String, RamId] =
    new InMemPathIdResolver[StoredGroup]("group", true, _.version)

  implicit val frameworkIdResolver = new IdResolver[String, FrameworkId, String, RamId] {
    override def toStorageId(id: String, version: Option[OffsetDateTime]): RamId =
      RamId(category, id, version)
    override val category: String = "framework-id"
    override def fromStorageId(key: RamId): String = key.id
    override val hasVersions = false
    override def version(v: FrameworkId): OffsetDateTime = OffsetDateTime.MIN
  }

  implicit val eventSubscribersResolver = new IdResolver[String, EventSubscribers, String, RamId] {
    override def toStorageId(id: String, version: Option[OffsetDateTime]): RamId =
      RamId(category, id, version)
    override val category: String = "event-subscribers"
    override def fromStorageId(key: RamId): String = key.id
    override val hasVersions = true
    override def version(v: EventSubscribers): OffsetDateTime = OffsetDateTime.MIN
  }
}

object InMemoryStoreSerialization extends InMemoryStoreSerialization
