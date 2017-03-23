package mesosphere.marathon
package storage.store

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.util.ByteString
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.{ DeploymentPlanDefinition, MarathonTask, ServiceDefinition }
import mesosphere.marathon.core.event.EventSubscribers
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.store.IdResolver
import mesosphere.marathon.core.storage.store.impl.zk.{ ZkId, ZkSerialized }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.raml.{ Pod, Raml }
import mesosphere.marathon.state.{ AppDefinition, PathId, TaskFailure }
import mesosphere.marathon.storage.repository.{ StoredGroup, StoredGroupRepositoryImpl, StoredPlan }
import mesosphere.util.state.FrameworkId
import play.api.libs.json.Json

trait ZkStoreSerialization {
  /** General id resolver for a key of Path.Id */
  class ZkPathIdResolver[T](
    val category: String,
    val hasVersions: Boolean,
    getVersion: (T) => OffsetDateTime)
      extends IdResolver[PathId, T, String, ZkId] {
    override def toStorageId(id: PathId, version: Option[OffsetDateTime]): ZkId =
      ZkId(category, id.path.mkString("_"), version)
    override def fromStorageId(key: ZkId): PathId = PathId(key.id.split("_").toList, absolute = true)
    override def version(v: T): OffsetDateTime = getVersion(v)
  }

  implicit val appDefResolver: IdResolver[PathId, AppDefinition, String, ZkId] =
    new ZkPathIdResolver[AppDefinition]("apps", true, _.version.toOffsetDateTime)

  implicit val appDefMarshaller: Marshaller[AppDefinition, ZkSerialized] =
    Marshaller.opaque(appDef => ZkSerialized(ByteString(appDef.toProtoByteArray)))

  implicit val appDefUnmarshaller: Unmarshaller[ZkSerialized, AppDefinition] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        val proto = ServiceDefinition.PARSER.parseFrom(byteString.toArray)
        AppDefinition.fromProto(proto)
    }

  implicit val podDefResolver: IdResolver[PathId, PodDefinition, String, ZkId] =
    new ZkPathIdResolver[PodDefinition]("pods", true, _.version.toOffsetDateTime)

  implicit val podDefMarshaller: Marshaller[PodDefinition, ZkSerialized] =
    Marshaller.opaque { podDef =>
      ZkSerialized(ByteString(Json.stringify(Json.toJson(Raml.toRaml(podDef))), "UTF-8"))
    }

  implicit val podDefUnmarshaller: Unmarshaller[ZkSerialized, PodDefinition] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        Raml.fromRaml(Json.parse(byteString.utf8String).as[Pod])
    }

  implicit val instanceResolver: IdResolver[Instance.Id, Instance, String, ZkId] =
    new IdResolver[Instance.Id, Instance, String, ZkId] {
      override def toStorageId(id: Id, version: Option[OffsetDateTime]): ZkId =
        ZkId(category, id.idString, version)
      override val category: String = "instance"
      override def fromStorageId(key: ZkId): Id = Instance.Id(key.id)
      override val hasVersions: Boolean = false
      override def version(v: Instance): OffsetDateTime = OffsetDateTime.MIN
    }

  implicit val instanceMarshaller: Marshaller[Instance, ZkSerialized] =
    Marshaller.opaque { instance =>
      ZkSerialized(ByteString(Json.stringify(Json.toJson(instance)), "UTF-8"))
    }

  implicit val instanceUnmarshaller: Unmarshaller[ZkSerialized, Instance] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        Json.parse(byteString.utf8String).as[Instance]
    }

  implicit val taskResolver: IdResolver[Task.Id, Task, String, ZkId] =
    new IdResolver[Task.Id, Task, String, ZkId] {
      override def toStorageId(id: Task.Id, version: Option[OffsetDateTime]): ZkId =
        ZkId(category, id.idString, version)
      override val category: String = "task"
      override def fromStorageId(key: ZkId): Task.Id = Task.Id(key.id)
      override val hasVersions = false
      override def version(v: Task): OffsetDateTime = OffsetDateTime.MIN
    }

  implicit val taskMarshaller: Marshaller[Task, ZkSerialized] =
    Marshaller.opaque(task => ZkSerialized(ByteString(TaskSerializer.toProto(task).toByteArray)))

  implicit val taskUnmarshaller: Unmarshaller[ZkSerialized, Task] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        TaskSerializer.fromProto(MarathonTask.parseFrom(byteString.toArray))
    }

  implicit val deploymentResolver: IdResolver[String, StoredPlan, String, ZkId] =
    new IdResolver[String, StoredPlan, String, ZkId] {
      override def toStorageId(id: String, version: Option[OffsetDateTime]): ZkId =
        ZkId(category, id, version)
      override val category: String = "deployment"
      override def fromStorageId(key: ZkId): String = key.id
      override val hasVersions = false
      override def version(v: StoredPlan): OffsetDateTime = OffsetDateTime.MIN
    }

  implicit val deploymentMarshaller: Marshaller[StoredPlan, ZkSerialized] =
    Marshaller.opaque(plan => ZkSerialized(ByteString(plan.toProto.toByteArray)))

  implicit val deploymentUnmarshaller: Unmarshaller[ZkSerialized, StoredPlan] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        StoredPlan(DeploymentPlanDefinition.parseFrom(byteString.toArray))
    }

  implicit val taskFailureResolver: IdResolver[PathId, TaskFailure, String, ZkId] =
    new ZkPathIdResolver[TaskFailure]("taskFailures", true, _.version.toOffsetDateTime)

  implicit val taskFailureMarshaller: Marshaller[TaskFailure, ZkSerialized] =
    Marshaller.opaque(failure => ZkSerialized(ByteString(failure.toProtoByteArray)))

  implicit val taskFailureUnmarshaller: Unmarshaller[ZkSerialized, TaskFailure] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        TaskFailure(Protos.TaskFailure.parseFrom(byteString.toArray))
    }

  implicit val groupIdResolver: IdResolver[PathId, StoredGroup, String, ZkId] =
    new IdResolver[PathId, StoredGroup, String, ZkId] {
      override def toStorageId(id: PathId, version: Option[OffsetDateTime]): ZkId = {
        require(id == StoredGroupRepositoryImpl.RootId)
        ZkId(category, "root", version)
      }
      override val category: String = "group"
      override def fromStorageId(key: ZkId): PathId = StoredGroupRepositoryImpl.RootId
      override val hasVersions = true
      override def version(v: StoredGroup): OffsetDateTime = v.version
    }

  implicit val groupMarshaller: Marshaller[StoredGroup, ZkSerialized] =
    Marshaller.opaque { group =>
      val proto = group.toProto
      require(proto.getDeprecatedAppsCount == 0)
      ZkSerialized(ByteString(proto.toByteArray))
    }

  implicit val groupUnmarshaller: Unmarshaller[ZkSerialized, StoredGroup] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        StoredGroup(Protos.GroupDefinition.parseFrom(byteString.toArray))
    }

  implicit val frameworkIdResolver = new IdResolver[String, FrameworkId, String, ZkId] {
    override def toStorageId(id: String, version: Option[OffsetDateTime]): ZkId =
      ZkId(category, id, version)
    override val category: String = "framework-id"
    override def fromStorageId(key: ZkId): String = key.id
    override val hasVersions = false
    override def version(v: FrameworkId): OffsetDateTime = OffsetDateTime.MIN
  }

  implicit val frameworkIdMarshaller: Marshaller[FrameworkId, ZkSerialized] =
    Marshaller.opaque(id => ZkSerialized(ByteString(id.toProtoByteArray)))

  implicit val frameworkIdUnmarshaller: Unmarshaller[ZkSerialized, FrameworkId] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        FrameworkId.fromProtoBytes(byteString.toArray)
    }

  implicit val eventSubscribersResolver = new IdResolver[String, EventSubscribers, String, ZkId] {
    override def toStorageId(id: String, version: Option[OffsetDateTime]): ZkId =
      ZkId(id, category, version)
    override val category: String = "event-subscribers"
    override def fromStorageId(key: ZkId): String = key.id
    override val hasVersions = false
    override def version(v: EventSubscribers): OffsetDateTime = OffsetDateTime.MIN
  }

  implicit val eventSubscribersMarshaller: Marshaller[EventSubscribers, ZkSerialized] =
    Marshaller.opaque(es => ZkSerialized(ByteString(es.toProtoByteArray)))

  implicit val eventSubscribersUnmarshaller: Unmarshaller[ZkSerialized, EventSubscribers] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        EventSubscribers().mergeFromProto(byteString.toArray)
    }
}

object ZkStoreSerialization extends ZkStoreSerialization
