package mesosphere.marathon.core.storage.impl.memory

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import mesosphere.marathon.core.storage.IdResolver
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{AppDefinition, PathId, TaskFailure}
import mesosphere.marathon.upgrade.DeploymentPlan

case class RamId(category: String, id: String, version: Option[OffsetDateTime])

case class Identity(value: Any)

trait InMemoryStoreSerialization {
  implicit def marshaller[V]: Marshaller[V, Identity] = Marshaller.opaque { a: V => Identity(a) }

  implicit def unmarshaller[V]: Unmarshaller[Identity, V] =
    Unmarshaller.strict { a: Identity => a.value.asInstanceOf[V] }

  private class InMemPathIdResolver[T](
    val category: String,
    val maxVersions: Int,
    getVersion: T => OffsetDateTime)
      extends IdResolver[PathId, T, String, RamId] {
    override def toStorageId(id: PathId, version: Option[OffsetDateTime]): RamId =
      RamId(category, id.path.mkString("_"), version)

    override def fromStorageId(key: RamId): PathId = PathId(key.id.split("_").toList, absolute = true)

    override def version(v: T): OffsetDateTime = getVersion(v)
  }

  def appDefResolver(maxVersions: Int): IdResolver[PathId, AppDefinition, String, RamId] =
    new InMemPathIdResolver[AppDefinition]("app", maxVersions, _.version.toOffsetDateTime)

  implicit val taskResolver: IdResolver[Task.Id, Task, String, RamId] =
    new IdResolver[Task.Id, Task, String, RamId] {
      override def toStorageId(id: Task.Id, version: Option[OffsetDateTime]): RamId =
        RamId(category, id.idString, version)
      override val category: String = "task"
      override def fromStorageId(key: RamId): Task.Id = Task.Id(key.id)
      override val maxVersions: Int = 0
      override def version(v: Task): OffsetDateTime = OffsetDateTime.MIN
    }

  implicit val deploymentResolver: IdResolver[String, DeploymentPlan, String, RamId] =
    new IdResolver[String, DeploymentPlan, String, RamId] {
      override def toStorageId(id: String, version: Option[OffsetDateTime]): RamId =
        RamId(category, id, version)
      override val category: String = "deployment"
      override def fromStorageId(key: RamId): String = key.id
      override val maxVersions: Int = 0
      override def version(v: DeploymentPlan): OffsetDateTime = OffsetDateTime.MIN
    }

  def taskFailureResolver(maxVersions: Int): IdResolver[PathId, TaskFailure, String, RamId] =
    new InMemPathIdResolver[TaskFailure]("taskfailure", maxVersions, _.version.toOffsetDateTime)
}

object InMemoryStoreSerialization extends InMemoryStoreSerialization
