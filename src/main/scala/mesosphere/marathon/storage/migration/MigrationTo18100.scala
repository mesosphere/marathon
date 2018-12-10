package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.Done
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.v2.json.Formats
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Reservation}
import mesosphere.marathon.core.instance.Instance.{AgentInfo, Id, InstanceState, agentFormat}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.{Instance, Timestamp}
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import org.apache.mesos.{Protos => MesosProtos}

import scala.concurrent.{ExecutionContext, Future}

class MigrationTo18100(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    MigrationTo18100.migrateInstanceCondition(instanceRepository, persistenceStore)
  }
}

object MigrationTo18100 extends MaybeStore with StrictLogging {

  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat

  /**
    * Read format for instance state with reserved condition.
    */
  val migrationConditionReader = new Reads[Condition] {
    private def readString(j: JsReadable) = j.validate[String].map {

      case reserved if reserved.toLowerCase == "reserved" => Condition.Finished
      case reservedTerminal if reservedTerminal.toLowerCase == "reservedterminal" => Condition.Finished
      case other =>
        logger.info(s"Migrate $other")
        Condition(other)
    }
    override def reads(json: JsValue): JsResult[Condition] =
      readString(json).orElse {
        json.validate[JsObject].flatMap { obj => readString(obj \ "str") }
      }
  }

  val instanceStateReads17: Reads[InstanceState] = {
    (
      (__ \ "condition").read[Condition](migrationConditionReader) ~
      (__ \ "since").read[Timestamp] ~
      (__ \ "activeSince").readNullable[Timestamp] ~
      (__ \ "healthy").readNullable[Boolean]
    ) { (condition, since, activeSince, healthy) =>
        InstanceState(condition, since, activeSince, healthy, Goal.Running)
      }
  }

  val taskStatusReads17: Reads[Task.Status] = {
    (
      (__ \ "stagedAt").read[Timestamp] ~
      (__ \ "startedAt").readNullable[Timestamp] ~
      (__ \ "mesosStatus").readNullable[MesosProtos.TaskStatus](Task.Status.MesosTaskStatusFormat) ~
      (__ \ "condition").read[Condition](migrationConditionReader) ~
      (__ \ "networkInfo").read[NetworkInfo](Formats.TaskStatusNetworkInfoFormat)

    ) { (stagedAt, startedAt, mesosStatus, condition, networkInfo) =>
        Task.Status(stagedAt, startedAt, mesosStatus, condition, networkInfo)
      }
  }

  val taskReads17: Reads[Task] = {
    (
      (__ \ "taskId").read[Task.Id] ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "status").read[Task.Status](taskStatusReads17)
    ) { (taskId, runSpecVersion, status) =>
        Task(taskId, runSpecVersion, status)
      }
  }

  val taskMapReads17: Reads[Map[Task.Id, Task]] = {
    mapReads(taskReads17).map {
      _.map { case (k, v) => Task.Id.parse(k) -> v }
    }
  }

  /**
    * Read format for old instance with reserved condition.
    */
  val instanceJsonReads17: Reads[Instance] = {
    (
      (__ \ "instanceId").read[Id] ~
      (__ \ "agentInfo").read[AgentInfo] ~
      (__ \ "tasksMap").read[Map[Task.Id, Task]](taskMapReads17) ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[InstanceState](instanceStateReads17) ~
      (__ \ "reservation").readNullable[Reservation]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, reservation) =>
        logger.info(s"Migrate $instanceId")
        new Instance(instanceId, Some(agentInfo), state, tasksMap, runSpecVersion, reservation)
      }
  }

  implicit val instanceResolver: IdResolver[Id, JsValue, String, ZkId] =
    new IdResolver[Id, JsValue, String, ZkId] {
      override def toStorageId(id: Id, version: Option[OffsetDateTime]): ZkId =
        ZkId(category, id.idString, version)

      override val category: String = "instance"

      override def fromStorageId(key: ZkId): Id = Id.fromIdString(key.id)

      override val hasVersions: Boolean = false

      override def version(v: JsValue): OffsetDateTime = OffsetDateTime.MIN
    }

  implicit val instanceJsonUnmarshaller: Unmarshaller[ZkSerialized, JsValue] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        Json.parse(byteString.utf8String)
    }

  /**
    * This function traverses all instances in ZK and sets the instance goal field.
    */
  def migrateInstanceCondition(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _])(implicit mat: Materializer): Future[Done] = {

    logger.info("Starting goal migration to Storage version 18.100")

    maybeStore(persistenceStore).map { store =>
      instanceRepository
        .ids()
        .mapAsync(1) { instanceId =>
          store.get[Id, JsValue](instanceId)
        }
        .via(migrationFlow)
        .mapAsync(1) { updatedInstance =>
          instanceRepository.store(updatedInstance)
        }
        .runWith(Sink.ignore)
    } getOrElse {
      Future.successful(Done)
    }
  }

  /**
    * Extract instance from old format with possible reserved.
    * @param jsValue The instance as JSON.
    * @return The parsed instance.
    */
  def extractInstanceFromJson(jsValue: JsValue): Instance = {
    println(s"+++ $jsValue")
    jsValue.as[Instance](instanceJsonReads17)
  }

  // This flow parses all provided instances and updates their goals. It does not save the updated instances.
  val migrationFlow = Flow[Option[JsValue]]
    .mapConcat {
      case Some(jsValue) => List(extractInstanceFromJson(jsValue))
      case None => Nil
    }
}
