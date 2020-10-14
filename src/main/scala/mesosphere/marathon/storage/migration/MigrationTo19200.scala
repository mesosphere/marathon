package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance.{AgentInfo, Id, InstanceState}
import mesosphere.marathon.core.instance.Reservation
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}

class MigrationTo19200(
    defaultMesosRole: Role,
    instanceRepository: InstanceRepository,
    persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]
) extends MigrationStep
    with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] =
    async {
      logger.info("Starting migration to 1.9.200")
      await(InstanceMigration.migrateInstances(instanceRepository, persistenceStore, instanceMigrationFlow))
    }

  /**
    * Read format for old instance without reservation id.
    */
  val instanceJsonReads18200: Reads[Instance] = {
    import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
    import mesosphere.marathon.core.instance.Instance.tasksMapFormat

    (
      (__ \ "instanceId").read[Id] ~
        (__ \ "agentInfo").readNullable[AgentInfo] ~
        (__ \ "tasksMap").read[Map[Task.Id, Task]] ~
        (__ \ "runSpecVersion").read[Timestamp] ~
        (__ \ "state").read[InstanceState] ~
        (__ \ "reservation").readNullable[Reservation] ~
        (__ \ "role").readNullable[String]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, reservation, persistedRole) =>
      logger.info(s"Migrate $instanceId")

      val role = persistedRole.orElse(Some(defaultMesosRole))

      new Instance(instanceId, agentInfo, state, tasksMap, runSpecVersion, reservation, role)
    }
  }

  /**
    * Extract instance from old format
    *
    * @param jsValue The instance as JSON.
    * @return The parsed instance.
    */
  def extractInstanceFromJson(jsValue: JsValue): Instance = jsValue.as[Instance](instanceJsonReads18200)

  val instanceMigrationFlow = Flow[JsValue].filter { jsValue =>
    // Only migrate instances that don't have a role
    (jsValue \ "role").isEmpty
  }.map(extractInstanceFromJson)
}
