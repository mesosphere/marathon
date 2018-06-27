package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.Done
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Instance, Reservation}
import mesosphere.marathon.core.instance.Instance.{AgentInfo, Id, InstanceState}
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.{Timestamp, UnreachableStrategy}
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json.{JsValue, Json, Reads}
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

import scala.concurrent.{ExecutionContext, Future}

class MigrationTo200(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    MigrationTo200.migrateInstanceGoals(instanceRepository, persistenceStore)
  }
}

object MigrationTo200 extends MaybeStore with StrictLogging {

  /**
    * This function traverses all instances in ZK and sets the instance goal field.
    */
  def migrateInstanceGoals(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _])(implicit mat: Materializer): Future[Done] = {

    logger.info("Starting reservations migration to Storage version 200")

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

    import Instance.agentFormat
    import Instance.tasksMapFormat
    import mesosphere.marathon.api.v2.json.Formats.time

    /**
      * Read format for instance state without goal.
      */
    val instanceStateReads160: Reads[InstanceState] = {
      (
        (__ \ "condition").read[Condition] ~
        (__ \ "since").read[Timestamp] ~
        (__ \ "activeSince").readNullable[Timestamp] ~
        (__ \ "healthy").readNullable[Boolean]
      ) { (condition, since, activeSince, healthy) =>
          InstanceState(condition, since, activeSince, healthy)
        }
    }

    /**
      * Read format for old instance without goal.
      */
    val instanceJsonReads160: Reads[Instance] = {
      (
        (__ \ "instanceId").read[Instance.Id] ~
        (__ \ "agentInfo").read[AgentInfo] ~
        (__ \ "tasksMap").read[Map[Task.Id, Task]] ~
        (__ \ "runSpecVersion").read[Timestamp] ~
        (__ \ "state").read[InstanceState](instanceStateReads160) ~
        (__ \ "unreachableStrategy").readNullable[raml.UnreachableStrategy] ~
        (__ \ "reservation").readNullable[Reservation]
      ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, maybeUnreachableStrategy, reservation) =>
          val unreachableStrategy = maybeUnreachableStrategy.
            map(Raml.fromRaml(_)).getOrElse(UnreachableStrategy.default())
          new Instance(instanceId, agentInfo, state, tasksMap, runSpecVersion, unreachableStrategy, reservation)
        }
    }

    /**
      * Extract instance from old format without goal attached.
      * @param jsValue The instance as JSON.
      * @return The parsed instance.
      */
    def extractInstanceFromJson(jsValue: JsValue): List[Instance] = {
      val instance = jsValue.as[Instance](instanceJsonReads160)
      List(instance)
    }

    /**
      * Update the goal of the instance.
      * @param instance The old instance.
      * @return An instance with an updated goal.
      */
    def updateGoal(instance: Instance): List[Instance] = {
      val updatedInstanceState = if (!instance.hasReservation) { //TODO: instance.isResident
        instance.state.copy(goal = Goal.Running)
      } else {
        if (instance.isReserved && instance.tasksMap.values.forall { task => task.isTerminal }) {
          instance.state.copy(goal = Goal.Stopped)
        } else {
          instance.state.copy(goal = Goal.Running)
        }
      }

      List(instance.copy(state = updatedInstanceState))
    }

    maybeStore(persistenceStore).map { store =>
      instanceRepository
        .ids()
        .mapAsync(1) { instanceId =>
          store.get(instanceId)
        }
        // TODO: Filter out Nones
        .mapConcat {
          case Some(jsValue) =>
            extractInstanceFromJson(jsValue)
          case _ =>
            Nil
        }
        .mapConcat { instance =>
          updateGoal(instance)
        }
        .mapAsync(1) { updatedInstance =>
          instanceRepository.store(updatedInstance)
        }
        .runWith(Sink.ignore)
    } getOrElse {
      Future.successful(Done)
    }
  }
}
