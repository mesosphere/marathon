package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance.{AgentInfo, Id, InstanceState, agentFormat}
import mesosphere.marathon.core.instance.Reservation
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{Instance, Timestamp}
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

import scala.concurrent.{ExecutionContext, Future}

class MigrationTo18200(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _])
    extends MigrationStep
    with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    InstanceMigration.migrateInstances(instanceRepository, persistenceStore, MigrationTo18200.migrationFlow)
  }
}

object MigrationTo18200 extends StrictLogging {

  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
  import mesosphere.marathon.core.instance.Instance.tasksMapFormat

  /**
    * Read format for old instance without reservation id.
    */
  val instanceJsonReads18100: Reads[Instance] = {
    (
      (__ \ "instanceId").read[Id] ~
        (__ \ "agentInfo").read[AgentInfo] ~
        (__ \ "tasksMap").read[Map[Task.Id, Task]] ~
        (__ \ "runSpecVersion").read[Timestamp] ~
        (__ \ "state").read[InstanceState] ~
        (__ \ "reservation").readNullable[JsObject]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, rawReservation) =>
      logger.info(s"Migrate $instanceId")

      val reservation = rawReservation.map { raw =>
        raw.as[Reservation](InstanceMigration.legacyReservationReads(tasksMap, instanceId))
      }
      new Instance(instanceId, Some(agentInfo), state, tasksMap, runSpecVersion, reservation, None)
    }
  }

  /**
    * Extract instance from old format with possible reservation id missing.
    *
    * @param jsValue The instance as JSON.
    * @return The parsed instance.
    */
  def extractInstanceFromJson(jsValue: JsValue): Instance = jsValue.as[Instance](instanceJsonReads18100)

  val migrationFlow = Flow[JsValue].filter { jsValue =>
    // Only migrate instances that have a reservation but not id defined.
    (jsValue \ "reservation").isDefined && (jsValue \ "reservation" \ "id").isEmpty
  }.map(extractInstanceFromJson)
}
