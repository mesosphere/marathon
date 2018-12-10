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
import mesosphere.marathon.core.instance.Instance.{agentFormat, AgentInfo, Id, InstanceState}
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.{Instance, Timestamp}
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json.{JsValue, Json, Reads}
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
    * Read format for instance state with reserved condition.
    */
  val migrationConditionReader = new Reads[Condition] {
    private def readString(j: JsReadable) = j.validate[String].map {

      case created if created.toLowerCase == "reserved" => Condition.Finished
      case created if created.toLowerCase == "reservedterminal" => Condition.Finished
      case other => Condition(other)
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

  /**
    * Read format for old instance with reserved condition.
    */
  val instanceJsonReads17: Reads[Instance] = {
    (
      (__ \ "instanceId").read[Id] ~
      (__ \ "agentInfo").read[AgentInfo] ~
      (__ \ "tasksMap").read[Map[Task.Id, Task]] ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[InstanceState](instanceStateReads17) ~
      (__ \ "reservation").readNullable[Reservation]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, reservation) =>
      new Instance(instanceId, Some(agentInfo), state, tasksMap, runSpecVersion, reservation)
    }
  }

  /**
    * Extract instance from old format without goal attached.
    * @param jsValue The instance as JSON.
    * @return The parsed instance.
    */
  def extractInstanceFromJson(jsValue: JsValue): Instance = jsValue.as[Instance](instanceJsonReads17)

  // This flow parses all provided instances and updates their goals. It does not save the updated instances.
  val migrationFlow = Flow[Option[JsValue]]
    .mapConcat {
      case Some(jsValue) => List(extractInstanceFromJson(jsValue))
      case None => Nil
    }
}
