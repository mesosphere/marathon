package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.v2.json.Formats
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Reservation}
import mesosphere.marathon.core.instance.Instance.{AgentInfo, Id, InstanceState, agentFormat}
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.task.{Task, TaskCondition}
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
    InstanceMigration.migrateInstances(instanceRepository, persistenceStore, MigrationTo18100.migrationFlow)
  }
}

object MigrationTo18100 extends StrictLogging {

  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat

  val instanceStateReads17: Reads[InstanceState] = {
    (
      (__ \ "since").read[Timestamp] ~
      (__ \ "activeSince").readNullable[Timestamp] ~
      (__ \ "healthy").readNullable[Boolean]
    ) { (since, activeSince, healthy) =>
        // The Unknown condition is updated in a later step.
        InstanceState(Condition.Unknown, since, activeSince, healthy, Goal.Running)
      }
  }

  val taskStatusReads17: Reads[Task.Status] = {
    (
      (__ \ "stagedAt").read[Timestamp] ~
      (__ \ "startedAt").readNullable[Timestamp] ~
      (__ \ "mesosStatus").readNullable[MesosProtos.TaskStatus](Task.Status.MesosTaskStatusFormat) ~
      (__ \ "networkInfo").read[NetworkInfo](Formats.TaskStatusNetworkInfoFormat)

    ) { (stagedAt, startedAt, mesosStatus, networkInfo) =>
        // We are migrating only Reserved and ReservedTerminal tasks. That's why it's safe to set it to `Finished`.
        val condition = mesosStatus.map(TaskCondition(_)).getOrElse(Condition.Finished)
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

        // Override Condition.Unknown with inferred condition.
        val condition = tasksMap.valuesIterator.map(_.status.condition).minBy(InstanceState.conditionHierarchy)
        val updatedState = state.copy(condition = condition)
        new Instance(instanceId, Some(agentInfo), updatedState, tasksMap, runSpecVersion, reservation)
      }
  }

  /**
    * Extract instance from old format with possible reserved.
    * @param jsValue The instance as JSON.
    * @return The parsed instance.
    */
  def extractInstanceFromJson(jsValue: JsValue): Instance = jsValue.as[Instance](instanceJsonReads17)

  val migrationFlow = Flow[JsValue]
    .filter { jsValue =>
      (jsValue \ "state" \ "condition" \ "str").orElse(jsValue \ "state" \ "condition")
        .validate[String].map { condition =>
          (condition.toLowerCase == "reserved" || condition.toLowerCase() == "reservedterminal")
        }
        .getOrElse(false)
    }
    .map(extractInstanceFromJson)
}
