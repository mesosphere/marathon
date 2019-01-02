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
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import org.apache.mesos.{Protos => MesosProtos}

import scala.concurrent.{ExecutionContext, Future}

class MigrationTo18(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    InstanceMigration.migrateInstances(instanceRepository, persistenceStore, MigrationTo18.migrationFlow)
  }
}

object MigrationTo18 extends StrictLogging {

  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat

  sealed trait ModificationStatus
  case object Modified extends ModificationStatus
  case object NotModified extends ModificationStatus

  case class ParsedValue[+A](value: A, status: ModificationStatus) {
    def map[B](f: A => B): ParsedValue[B] = ParsedValue(f(value), status)
    def isModified: Boolean = status == Modified
  }

  val migrationConditionReader = new Reads[ParsedValue[Condition]] {
    private def readString(j: JsReadable): JsResult[ParsedValue[Condition]] = j.validate[String].map {

      case created if created.toLowerCase == "created" => ParsedValue(Condition.Provisioned, Modified)
      case other => ParsedValue(Condition(other), NotModified)
    }
    override def reads(json: JsValue): JsResult[ParsedValue[Condition]] =
      readString(json).orElse {
        json.validate[JsObject].flatMap { obj => readString(obj \ "str") }
      }
  }

  /**
    * Read format for instance state where we replace created condition to provisioned.
    */
  val instanceStateReads17: Reads[ParsedValue[InstanceState]] = {
    (
      (__ \ "condition").read[ParsedValue[Condition]](migrationConditionReader) ~
      (__ \ "since").read[Timestamp] ~
      (__ \ "activeSince").readNullable[Timestamp] ~
      (__ \ "healthy").readNullable[Boolean] ~
      (__ \ "goal").read[Goal]
    ) { (condition, since, activeSince, healthy, goal) =>

        condition.map { c =>
          InstanceState(c, since, activeSince, healthy, goal)
        }
      }
  }

  val taskStatusReads17: Reads[ParsedValue[Task.Status]] = {
    (
      (__ \ "stagedAt").read[Timestamp] ~
      (__ \ "startedAt").readNullable[Timestamp] ~
      (__ \ "mesosStatus").readNullable[MesosProtos.TaskStatus](Task.Status.MesosTaskStatusFormat) ~
      (__ \ "condition").read[ParsedValue[Condition]](migrationConditionReader) ~
      (__ \ "networkInfo").read[NetworkInfo](Formats.TaskStatusNetworkInfoFormat)

    ) { (stagedAt, startedAt, mesosStatus, condition, networkInfo) =>
        condition.map { c =>
          Task.Status(stagedAt, startedAt, mesosStatus, c, networkInfo)
        }

      }
  }

  val taskReads17: Reads[ParsedValue[Task]] = {
    (
      (__ \ "taskId").read[Task.Id] ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "status").read[ParsedValue[Task.Status]](taskStatusReads17)
    ) { (taskId, runSpecVersion, status) =>
        status.map { s =>
          Task(taskId, runSpecVersion, s)
        }
      }
  }

  val taskMapReads17: Reads[ParsedValue[Map[Task.Id, Task]]] = {

    mapReads(taskReads17).map {
      _.map { case (k, v) => Task.Id.parse(k) -> v }
    }
      .map { taskMap =>
        if (taskMap.values.exists(_.isModified)) {
          ParsedValue(taskMap.mapValues(_.value), Modified)
        } else {
          ParsedValue(taskMap.mapValues(_.value), NotModified)
        }
      }
  }

  /**
    * Read format for old instance without goal.
    */
  val instanceJsonReads17: Reads[ParsedValue[Instance]] = {
    (
      (__ \ "instanceId").read[Id] ~
      (__ \ "agentInfo").read[AgentInfo] ~
      (__ \ "tasksMap").read[ParsedValue[Map[Task.Id, Task]]](taskMapReads17) ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[ParsedValue[InstanceState]](instanceStateReads17) ~
      (__ \ "reservation").readNullable[Reservation]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, reservation) =>

        if (List(state, tasksMap).exists(_.isModified)) {
          val instance = new Instance(instanceId, Some(agentInfo), state.value, tasksMap.value, runSpecVersion, reservation)
          ParsedValue(instance, Modified)
        } else {
          val instance = new Instance(instanceId, Some(agentInfo), state.value, tasksMap.value, runSpecVersion, reservation)
          ParsedValue(instance, NotModified)
        }

      }
  }

  /**
    * Extract instance from old format with replaced condition.
    * @param jsValue The instance as JSON.
    * @return The parsed instance.
    */
  def extractInstanceFromJson(jsValue: JsValue): ParsedValue[Instance] = jsValue.as[ParsedValue[Instance]](instanceJsonReads17)

  val migrationFlow = Flow[JsValue]
    .map(extractInstanceFromJson)
    .mapConcat {
      case ParsedValue(instance, Modified) =>
        logger.info(s"${instance.instanceId} had `Created` condition, migration necessary")
        List(instance)
      case ParsedValue(instance, NotModified) =>
        logger.info(s"${instance.instanceId} doesn't need to be migrated")
        Nil
    }
}
