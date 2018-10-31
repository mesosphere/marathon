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
import mesosphere.marathon.core.instance.{Goal, Instance, Reservation}
import mesosphere.marathon.core.instance.Instance.{AgentInfo, Id, InstanceState}
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.{Timestamp, UnreachableStrategy}
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json.{JsValue, Json, Reads}
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import org.apache.mesos.{Protos => MesosProtos}

import scala.concurrent.{ExecutionContext, Future}

class MigrationTo17(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    MigrationTo17.migrateInstanceGoals(instanceRepository, persistenceStore)
  }
}

object MigrationTo17 extends MaybeStore with StrictLogging {

  import Instance.agentFormat
  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat

  /**
    * Read format for instance state without goal.
    */

  val migrationConditionReader = new Reads[Condition] {
    private def readString(j: JsReadable) = j.validate[String].map {

      case created if created.toLowerCase == "created" => Condition.Provisioned
      case other => Condition(other)
    }
    override def reads(json: JsValue): JsResult[Condition] =
      readString(json).orElse {
        json.validate[JsObject].flatMap { obj => readString(obj \ "str") }
      }
  }

  val instanceStateReads160: Reads[InstanceState] = {
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
      _.map { case (k, v) => Task.Id(k) -> v }
    }
  }

  /**
    * Read format for old instance without goal.
    */
  val instanceJsonReads160: Reads[Instance] = {
    (
      (__ \ "instanceId").read[Instance.Id] ~
      (__ \ "agentInfo").read[AgentInfo] ~
      (__ \ "tasksMap").read[Map[Task.Id, Task]](taskMapReads17) ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[InstanceState](instanceStateReads160) ~
      (__ \ "unreachableStrategy").readNullable[raml.UnreachableStrategy] ~
      (__ \ "reservation").readNullable[Reservation]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, maybeUnreachableStrategy, reservation) =>
        val unreachableStrategy = maybeUnreachableStrategy.
          map(Raml.fromRaml(_)).getOrElse(UnreachableStrategy.default())
        new Instance(instanceId, Some(agentInfo), state, tasksMap, runSpecVersion, unreachableStrategy, reservation)
      }
  }

  implicit val instanceResolver: IdResolver[Instance.Id, JsValue, String, ZkId] =
    new IdResolver[Instance.Id, JsValue, String, ZkId] {
      override def toStorageId(id: Id, version: Option[OffsetDateTime]): ZkId =
        ZkId(category, id.idString, version)

      override val category: String = "instance"

      override def fromStorageId(key: ZkId): Id = Instance.Id.fromIdString(key.id)

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
  def migrateInstanceGoals(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _])(implicit mat: Materializer): Future[Done] = {

    logger.info("Starting goal migration to Storage version 200")

    maybeStore(persistenceStore).map { store =>
      instanceRepository
        .ids()
        .mapAsync(1) { instanceId =>
          store.get[Instance.Id, JsValue](instanceId)
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
    * Update the goal of the instance.
    * @param instance The old instance.
    * @return An instance with an updated goal.
    */
  def updateGoal(instance: Instance): Instance = {
    val updatedInstanceState = if (!instance.hasReservation) {
      instance.state.copy(goal = Goal.Running)
    } else {
      if (instance.isReservedTerminal) {
        instance.state.copy(goal = Goal.Stopped)
      } else {
        instance.state.copy(goal = Goal.Running)
      }
    }

    instance.copy(state = updatedInstanceState)
  }

  /**
    * Extract instance from old format without goal attached.
    * @param jsValue The instance as JSON.
    * @return The parsed instance.
    */
  def extractInstanceFromJson(jsValue: JsValue): Instance = jsValue.as[Instance](instanceJsonReads160)

  // This flow parses all provided instances and updates their goals. It does not save the updated instances.
  val migrationFlow = Flow[Option[JsValue]]
    .mapConcat {
      case Some(jsValue) => List(extractInstanceFromJson(jsValue))
      case None => Nil
    }
    .map { instance =>
      updateGoal(instance)
    }
}
