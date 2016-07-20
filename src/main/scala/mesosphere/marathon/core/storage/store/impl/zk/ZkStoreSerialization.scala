package mesosphere.marathon.core.storage.store.impl.zk

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.util.ByteString
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.{ DeploymentPlanDefinition, MarathonTask, ServiceDefinition }
import mesosphere.marathon.core.storage.repository.impl.StoredGroup
import mesosphere.marathon.core.storage.store.IdResolver
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.state.{ AppDefinition, PathId, TaskFailure }
import mesosphere.marathon.upgrade.DeploymentPlan

case class ZkId(category: String, id: String, version: Option[OffsetDateTime]) {
  private val bucket = math.abs(id.hashCode % ZkStoreSerialization.HashBucketSize)
  def path: String = version.fold(f"/$category/$bucket%x/$id") { v =>
    f"/$category/$bucket%x/$id/versions/$v"
  }
}

case class ZkSerialized(bytes: ByteString)

trait ZkStoreSerialization {
  /** General id resolver for a key of Path.Id */
  private class ZkPathIdResolver[T](
    val category: String,
    val maxVersions: Int,
    getVersion: (T) => OffsetDateTime)
      extends IdResolver[PathId, T, String, ZkId] {
    override def toStorageId(id: PathId, version: Option[OffsetDateTime]): ZkId =
      ZkId(category, id.path.mkString("_"), version)
    override def fromStorageId(key: ZkId): PathId = PathId(key.id.split("_").toList, absolute = true)
    override def version(v: T): OffsetDateTime = getVersion(v)
  }

  def appDefResolver(maxVersions: Int): IdResolver[PathId, AppDefinition, String, ZkId] =
    new ZkPathIdResolver[AppDefinition]("apps", maxVersions, _.version.toOffsetDateTime)

  implicit val appDefMarshaller: Marshaller[AppDefinition, ZkSerialized] =
    Marshaller.opaque(appDef => ZkSerialized(ByteString(appDef.toProtoByteArray)))

  implicit val appDefUnmarshaller: Unmarshaller[ZkSerialized, AppDefinition] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        val proto = ServiceDefinition.PARSER.parseFrom(byteString.toArray)
        AppDefinition.fromProto(proto)
    }

  implicit val taskResolver: IdResolver[Task.Id, Task, String, ZkId] =
    new IdResolver[Task.Id, Task, String, ZkId] {
      override def toStorageId(id: Task.Id, version: Option[OffsetDateTime]): ZkId =
        ZkId(category, id.idString, version)
      override val category: String = "task"
      override def fromStorageId(key: ZkId): Task.Id = Task.Id(key.id)
      override val maxVersions: Int = 0
      override def version(v: Task): OffsetDateTime = OffsetDateTime.MIN
    }

  implicit val taskMarshaller: Marshaller[Task, ZkSerialized] =
    Marshaller.opaque(task => ZkSerialized(ByteString(TaskSerializer.toProto(task).toByteArray)))

  implicit val taskUnmarshaller: Unmarshaller[ZkSerialized, Task] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        TaskSerializer.fromProto(MarathonTask.parseFrom(byteString.toArray))
    }

  implicit val deploymentResolver: IdResolver[String, DeploymentPlan, String, ZkId] =
    new IdResolver[String, DeploymentPlan, String, ZkId] {
      override def toStorageId(id: String, version: Option[OffsetDateTime]): ZkId =
        ZkId(category, id, version)
      override val category: String = "deployment"
      override def fromStorageId(key: ZkId): String = key.id
      override val maxVersions: Int = 0
      override def version(v: DeploymentPlan): OffsetDateTime = OffsetDateTime.MIN
    }

  implicit val deploymentMarshaller: Marshaller[DeploymentPlan, ZkSerialized] =
    Marshaller.opaque(plan => ZkSerialized(ByteString(plan.toProtoByteArray)))

  implicit val deploymentUnmarshaller: Unmarshaller[ZkSerialized, DeploymentPlan] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        DeploymentPlan.fromProto(DeploymentPlanDefinition.parseFrom(byteString.toArray))
    }

  def taskFailureResolver(maxVersions: Int): IdResolver[PathId, TaskFailure, String, ZkId] =
    new ZkPathIdResolver[TaskFailure]("taskFailures", maxVersions, _.version.toOffsetDateTime)

  implicit val taskFailureMarshaller: Marshaller[TaskFailure, ZkSerialized] =
    Marshaller.opaque(failure => ZkSerialized(ByteString(failure.toProtoByteArray)))

  implicit val taskFailureUnmarshaller: Unmarshaller[ZkSerialized, TaskFailure] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        TaskFailure(Protos.TaskFailure.parseFrom(byteString.toArray))
    }

  def groupIdResolver(maxVersions: Int): IdResolver[PathId, StoredGroup, String, ZkId] =
    new ZkPathIdResolver[StoredGroup]("group", maxVersions, _.version)

  implicit val groupMarshaller: Marshaller[StoredGroup, ZkSerialized] =
    Marshaller.opaque(group => ZkSerialized(ByteString(group.toProto.toByteArray)))

  implicit val groupUnmarshaller: Unmarshaller[ZkSerialized, StoredGroup] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        StoredGroup(Protos.StoredGroup.parseFrom(byteString.toArray))
    }
}

object ZkStoreSerialization extends ZkStoreSerialization {
  val HashBucketSize = 16
}
