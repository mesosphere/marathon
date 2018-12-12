package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.v2.json.Formats
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Reservation}
import mesosphere.marathon.core.instance.Instance.{agentFormat, AgentInfo, Id, InstanceState}
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.{Instance, Timestamp}
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json.{JsValue, Reads}
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import org.apache.mesos.{Protos => MesosProtos}

import scala.concurrent.{ExecutionContext, Future}

class MigrationTo17(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    InstanceMigration.migrateInstances(instanceRepository, persistenceStore, MigrationTo17.migrationFlow)
  }
}

object MigrationTo17 extends StrictLogging {

  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat

  /**
    * Read format for instance state with created condition.
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
      _.map { case (k, v) => Task.Id.parse(k) -> v }
    }
  }

  /**
    * Read format for old instance with created condition.
    */
  val instanceJsonReads160: Reads[Instance] = {
    (
      (__ \ "instanceId").read[Id] ~
      (__ \ "agentInfo").read[AgentInfo] ~
      (__ \ "tasksMap").read[Map[Task.Id, Task]](taskMapReads17) ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[InstanceState](instanceStateReads160) ~
      (__ \ "reservation").readNullable[Reservation]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, reservation) =>
        new Instance(instanceId, Some(agentInfo), state, tasksMap, runSpecVersion, reservation)
      }
  }

  /**
    * Update the goal of the instance.
    * @param instance The old instance.
    * @return An instance with an updated goal.
    */
  def updateGoal(instance: Instance): Instance = {
    val updatedInstanceState = if (!instance.reservation.isDefined) {
      instance.state.copy(goal = Goal.Running)
    } else {
      if (instance.hasReservation && instance.state.condition.isTerminal) {
        instance.state.copy(goal = Goal.Stopped)
      } else {
        instance.state.copy(goal = Goal.Running)
      }
    }

    instance.copy(state = updatedInstanceState)
  }

  /**
    * Extract instance from old format without goals.
    * @param jsValue The instance as JSON.
    * @return The parsed instance.
    */
  def extractInstanceFromJson(jsValue: JsValue): Instance = jsValue.as[Instance](instanceJsonReads160)

  // This flow parses all provided instances and updates their conditions. It does not save the updated instances.
  val migrationFlow = Flow[JsValue]
    .map { jsValue =>
      extractInstanceFromJson(jsValue)
    }
    .map { instance =>
      updateGoal(instance)
    }
}
