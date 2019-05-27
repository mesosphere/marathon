package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance.{AgentInfo, Id, InstanceState, agentFormat, tasksMapFormat}
import mesosphere.marathon.core.instance.{LocalVolumeId, Reservation}
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{Instance, Timestamp}
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

import scala.concurrent.{ExecutionContext, Future}

class MigrationTo18200(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    InstanceMigration.migrateInstances(instanceRepository, persistenceStore, MigrationTo18200.migrationFlow)
  }
}

object MigrationTo18200 extends StrictLogging {

  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat

  case class ReservationWithoutId(volumeIds: Seq[LocalVolumeId], state: Reservation.State)

  implicit val reservationWithoutIdReads: Reads[ReservationWithoutId] = Json.reads[ReservationWithoutId]

  def appTask(tasksMap: Map[Task.Id, Task]): Option[Task] = tasksMap.headOption.map(_._2)

  def inferReservationId(tasksMap: Map[Task.Id, Task], instanceId: Id): Reservation.Id = {
    if (tasksMap.nonEmpty) {
      val taskId = appTask(tasksMap).getOrElse(throw new IllegalStateException(s"No task in $instanceId")).taskId
      taskId match {
        case Task.LegacyId(runSpecId, separator: String, uuid) =>
          Reservation.LegacyId(runSpecId, separator, uuid)
        case Task.LegacyResidentId(runSpecId, separator, uuid, _) =>
          Reservation.LegacyId(runSpecId, separator, uuid)
        case Task.EphemeralTaskId(instanceId, _) =>
          Reservation.SimplifiedId(instanceId)
        case Task.TaskIdWithIncarnation(instanceId, _, _) =>
          Reservation.SimplifiedId(instanceId)
      }
    } else {
      Reservation.SimplifiedId(instanceId)
    }
  }

  /**
    * Read format for old instance without a reservation id.
    */
  val oldInstanceJsonReads: Reads[Instance] = {
    (
      (__ \ "instanceId").read[Id] ~
      (__ \ "agentInfo").read[AgentInfo] ~
      (__ \ "tasksMap").read[Map[Task.Id, Task]] ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[InstanceState] ~
      (__ \ "reservation").readNullable[ReservationWithoutId]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, reservation) =>
        logger.info(s"Migrate $instanceId")

        // Add reservation id.
        val migratedReservationWithId = reservation.map {
          case ReservationWithoutId(volumeIds, state) =>
            Reservation(volumeIds, state, inferReservationId(tasksMap, instanceId))
        }

        new Instance(instanceId, Some(agentInfo), state, tasksMap, runSpecVersion, migratedReservationWithId)
      }
  }

  /**
    * Extract instance from old format with without reservation id.
    * @param jsValue The instance as JSON.
    * @return The parsed instance.
    */
  def extractInstanceFromJson(jsValue: JsValue): Instance = jsValue.as[Instance](oldInstanceJsonReads)

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
