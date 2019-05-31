package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.{Done, NotUsed}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.instance.Reservation.{LegacyId, SimplifiedId}
import mesosphere.marathon.core.instance.{LocalVolumeId, Reservation}
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.storage.store.impl.cache.{LazyCachingPersistenceStore, LazyVersionCachingPersistentStore, LoadTimeCachingPersistenceStore}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkPersistenceStore, ZkSerialized}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Instance
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Provides a help to get access to the underlying ZooKeeper store of the provided persistence store.
  * See [[MigrationTo17]] for usage.
  */
trait MaybeStore {

  private def findZkStore(ps: PersistenceStore[_, _, _]): Option[ZkPersistenceStore] = {
    ps match {
      case zk: ZkPersistenceStore =>
        Some(zk)
      case lcps: LazyCachingPersistenceStore[_, _, _] =>
        findZkStore(lcps.store)
      case lvcps: LazyVersionCachingPersistentStore[_, _, _] =>
        findZkStore(lvcps.store)
      case ltcps: LoadTimeCachingPersistenceStore[_, _, _] =>
        findZkStore(ltcps.store)
      case other =>
        None
    }
  }

  /**
    * We're trying to find if we have a ZooKeeper store because it provides objects as byte arrays and this
    * makes serialization into json easier.
    */
  def maybeStore(persistenceStore: PersistenceStore[_, _, _]): Option[ZkPersistenceStore] = findZkStore(persistenceStore)

}

/**
  * Provides a common pattern to migrate instances. This makes testing migrations easier.
  */
object InstanceMigration extends MaybeStore with StrictLogging {

  /**
    * This function traverses all instances in ZK and passes them through the migration flow before saving the updates.
    */
  def migrateInstances(
    instanceRepository: InstanceRepository,
    persistenceStore: PersistenceStore[_, _, _],
    migrationFlow: Flow[JsValue, Instance, NotUsed])(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {

    val countingSink: Sink[Done, NotUsed] = Sink.fold[Int, Done](0) { case (count, Done) => count + 1 }
      .mapMaterializedValue { f =>
        f.map(i => logger.info(s"$i instances migrated"))
        NotUsed
      }

    maybeStore(persistenceStore).map { store =>
      instanceRepository
        .ids()
        .mapAsync(1) { instanceId =>
          store
            .get[Id, JsValue](instanceId)
            .map(maybeValue => maybeValue.orElse { logger.error(s"Failed to load an instance for $instanceId. It will be ignored"); None })
        }
        .collect {
          case Some(jsValue) => jsValue
        }
        .via(migrationFlow)
        .mapAsync(1) { updatedInstance =>
          instanceRepository.store(updatedInstance)
        }
        .alsoTo(countingSink)
        .runWith(Sink.ignore)
    } getOrElse {
      Future.successful(Done)
    }
  }

  /**
    * We copied the IdResolver and the unmarshaller so that changes to production won't impact any migration code. Some
    * migrations may have to override this resolver.
    */
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
    * JSON [[Reads]] format for reservations that have no id saved. See [[MigrationTo17]] for an example.
    *
    * @param tasksMap The tasks from the instance belonging to the reservation.
    * @param instanceId The id of the instance.
    * @return
    */
  def legacyReservationReads(tasksMap: Map[Task.Id, Task], instanceId: Id): Reads[Reservation] = {
    (
      (__ \ "volumeIds").read[Seq[LocalVolumeId]] ~
      (__ \ "state").read[Reservation.State] ~
      (__ \ "id").readNullable[Reservation.Id]
    ) { (volumesIds, state, maybeId) =>
        val reservationId = maybeId.getOrElse(inferReservationId(tasksMap, instanceId))
        Reservation(volumesIds, state, reservationId)
      }
  }

  /**
    * Infer the reservation id for an instance.
    *
    * In older Marathon versions the reservation id was the run spec path and the uuid of the instance
    * joined by a separator. Eg ephemeral tasks and persistent apps used it. This is expressed by
    * [[mesosphere.marathon.core.instance.Reservation.LegacyId]].
    *
    * Later Marathon versions used the instance id, ie `<run spec path>.marathon-<uuid>`, as the
    * reservation id. This is expressed by [[mesosphere.marathon.core.instance.Reservation.SimplifiedId]].
    * Notice the extra "marathon-" in the id string.
    *
    * The reservation id was in all cases determined by the `appTask.taskId`. The app task is just the
    * first task of the tasks map of an instance.
    *
    * This method is only used if the saved reservation does not include an id. This will be true for
    * all instance from apps and pods started with Marathon 1.8.194-1590825ea and earlier. All apps
    * and pods from later version will have a reservation id persisted.
    *
    * Future Marathon versions that only allow upgrades from storage version (1, 8, 200) and later can
    * drop the inference and should safely assume that all reservation have a persisted id.
    *
    * @param tasksMap All tasks of an instance.
    * @param instanceId The id of the instance this reservation belongs to.
    * @return The proper reservation id.
    */
  private[migration] def inferReservationId(tasksMap: Map[Task.Id, Task], instanceId: Id): Reservation.Id = {
    if (tasksMap.nonEmpty) {
      val taskId = appTask(tasksMap).getOrElse(throw new IllegalStateException(s"No task in $instanceId")).taskId
      taskId match {
        case Task.LegacyId(runSpecId, separator: String, uuid) => LegacyId(runSpecId, separator, uuid)
        case Task.LegacyResidentId(runSpecId, separator, uuid, _) => LegacyId(runSpecId, separator, uuid)
        case Task.EphemeralTaskId(instanceId, _) => SimplifiedId(instanceId)
        case Task.TaskIdWithIncarnation(instanceId, _, _) => SimplifiedId(instanceId)
      }
    } else {
      SimplifiedId(instanceId)
    }
  }

  def appTask(tasksMap: Map[Task.Id, Task]): Option[Task] = tasksMap.headOption.map(_._2)
}
