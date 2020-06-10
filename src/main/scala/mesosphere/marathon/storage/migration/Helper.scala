package mesosphere.marathon
package storage.migration

import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.instance.Reservation.{LegacyId, SimplifiedId}
import mesosphere.marathon.core.instance.{LocalVolumeId, Reservation}
import mesosphere.marathon.core.storage.store.impl.cache.{
  LazyCachingPersistenceStore,
  LazyVersionCachingPersistentStore,
  LoadTimeCachingPersistenceStore
}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkPersistenceStore, ZkSerialized}
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, Instance, Timestamp}
import mesosphere.marathon.storage.repository.InstanceRepository
import mesosphere.marathon.storage.store.ZkStoreSerialization
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

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
      migrationFlow: Flow[JsValue, Instance, NotUsed]
  )(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {

    val countingSink: Sink[Done, NotUsed] = Sink.fold[Int, Done](0) { case (count, Done) => count + 1 }.mapMaterializedValue { f =>
      f.map(i => logger.info(s"$i instances migrated"))
      NotUsed
    }

    maybeStore(persistenceStore).map { store =>
      instanceRepository
        .ids()
        .mapAsync(Migration.maxConcurrency) { instanceId =>
          store
            .get[Id, JsValue](instanceId)
            .map(maybeValue => maybeValue.orElse { logger.error(s"Failed to load an instance for $instanceId. It will be ignored"); None })
        }
        .collect {
          case Some(jsValue) => jsValue
        }
        .via(migrationFlow)
        .mapAsync(Migration.maxConcurrency) { updatedInstance =>
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
    * @return The reservation with an inferred id or the already persisted id.
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

object ServiceMigration extends StrictLogging with MaybeStore {

  /**
    * Helper method to migrate all versions for all apps by loading them, threading them through the provided flow, and
    * then storing them. Filtered entities are simply unaffected in persistence.
    *
    * @param storageVersion The storage version of the migration being run
    * @param persistenceStore Used to load and store the entities
    * @param migratingFlow The flow which actually performs the migrations.
    * @return
    */
  def migrateAppVersions(
      storageVersion: StorageVersion,
      persistenceStore: PersistenceStore[ZkId, String, ZkSerialized],
      migratingFlow: Flow[(Protos.ServiceDefinition, Option[OffsetDateTime]), (Protos.ServiceDefinition, Option[OffsetDateTime]), NotUsed]
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Done] = {
    implicit val appProtosUnmarshaller: Unmarshaller[ZkSerialized, Protos.ServiceDefinition] =
      Unmarshaller.strict {
        case ZkSerialized(byteString) => Protos.ServiceDefinition.parseFrom(byteString.toArray)
      }

    implicit val appProtosMarshaller: Marshaller[Protos.ServiceDefinition, ZkSerialized] =
      Marshaller.opaque(appProtos => ZkSerialized(ByteString(appProtos.toByteArray)))

    implicit val appIdResolver: IdResolver[AbsolutePathId, Protos.ServiceDefinition, String, ZkId] =
      new ZkStoreSerialization.ZkPathIdResolver[Protos.ServiceDefinition](
        "apps",
        true,
        AppDefinition.versionInfoFrom(_).version.toOffsetDateTime
      )

    val countingSink: Sink[Done, NotUsed] = Sink.fold[Int, Done](0) { case (count, Done) => count + 1 }.mapMaterializedValue { f =>
      f.map(i => logger.info(s"$i apps modified when migrating to ${storageVersion}"))
      NotUsed
    }

    maybeStore(persistenceStore).map { zkStore =>
      zkStore
        .ids()
        .flatMapConcat(appId => zkStore.versions(appId).map(v => (appId, Some(v))) ++ Source.single((appId, Option.empty[OffsetDateTime])))
        .mapAsync(Migration.maxConcurrency) {
          case (appId, Some(version)) => zkStore.get(appId, version).map(app => (app, Some(version)))
          case (appId, None) => zkStore.get(appId).map(app => (app, Option.empty[OffsetDateTime]))
        }
        .collect { case (Some(appProtos), optVersion) if !appProtos.hasRole => (appProtos, optVersion) }
        .via(migratingFlow)
        .mapAsync(Migration.maxConcurrency) {
          case (appProtos, Some(version)) => zkStore.store(AbsolutePathId(appProtos.getId), appProtos, version)
          case (appProtos, None) => zkStore.store(AbsolutePathId(appProtos.getId), appProtos)
        }
        .alsoTo(countingSink)
        .runWith(Sink.ignore)
    }.getOrElse {
      Future.successful(Done)
    }
  }

  /**
    * Helper method to migrate pod versions for all pods by loading them, threading them through the provided flow, and
    * then storing them. Filtered entities are simply unaffected in persistence.
    *
    * @param storageVersion The storage version of the migration being run
    * @param persistenceStore Used to load and store the entities
    * @param migratingFlow The flow which actually performs the migrations.
    * @return
    */
  def migratePodVersions(
      storageVersion: StorageVersion,
      persistenceStore: PersistenceStore[ZkId, String, ZkSerialized],
      migratingFlow: Flow[(raml.Pod, Option[OffsetDateTime]), (raml.Pod, Option[OffsetDateTime]), NotUsed]
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Done] = {
    implicit val podIdResolver =
      new ZkStoreSerialization.ZkPathIdResolver[raml.Pod]("pods", true, _.version.getOrElse(Timestamp.now().toOffsetDateTime))

    implicit val podJsonUnmarshaller: Unmarshaller[ZkSerialized, raml.Pod] =
      Unmarshaller.strict {
        case ZkSerialized(byteString) => Json.parse(byteString.utf8String).as[raml.Pod]
      }

    implicit val podRamlMarshaller: Marshaller[raml.Pod, ZkSerialized] =
      Marshaller.opaque { podRaml =>
        ZkSerialized(ByteString(Json.stringify(Json.toJson(podRaml)), StandardCharsets.UTF_8.name()))
      }

    val countingSink: Sink[Done, NotUsed] = Sink.fold[Int, Done](0) { case (count, Done) => count + 1 }.mapMaterializedValue { f =>
      f.map(i => logger.info(s"$i pods modified when migrating to ${storageVersion}"))
      NotUsed
    }

    maybeStore(persistenceStore).map { zkStore =>
      zkStore
        .ids()
        .flatMapConcat(podId => zkStore.versions(podId).map(v => (podId, Some(v))) ++ Source.single((podId, Option.empty[OffsetDateTime])))
        .mapAsync(Migration.maxConcurrency) {
          case (podId, Some(version)) => zkStore.get(podId, version).map(pod => (pod, Some(version)))
          case (podId, None) => zkStore.get(podId).map(pod => (pod, Option.empty[OffsetDateTime]))
        }
        .collect { case (Some(podRaml), optVersion) if podRaml.role.isEmpty => (podRaml, optVersion) }
        .via(migratingFlow)
        .mapAsync(Migration.maxConcurrency) {
          case (podRaml, Some(version)) => zkStore.store(AbsolutePathId(podRaml.id), podRaml, version)
          case (podRaml, None) => zkStore.store(AbsolutePathId(podRaml.id), podRaml)
        }
        .alsoTo(countingSink)
        .runWith(Sink.ignore)
    }.getOrElse {
      Future.successful(Done)
    }
  }
}
