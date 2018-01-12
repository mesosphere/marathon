package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.Done
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.{ Instance, Reservation }
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.storage.store.impl.cache.{ LazyCachingPersistenceStore, LazyVersionCachingPersistentStore, LoadTimeCachingPersistenceStore }
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.core.storage.store.impl.zk.{ ZkId, ZkPersistenceStore, ZkSerialized }
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json.{ JsValue, Json }

import scala.concurrent.{ ExecutionContext, Future }

@SuppressWarnings(Array("ClassNames"))
class MigrationTo160(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _])(implicit ctx: ExecutionContext, mat: Materializer) extends StrictLogging {

  def migrate(): Future[Done] = {
    MigrationTo160.migrateReservations(instanceRepository, persistenceStore)
  }
}

object MigrationTo160 extends StrictLogging {
  /**
    * This function traverse all the instances in the ZK, and moves reservation from task to the instance level
    *
    * @param instanceRepository
    * @param persistenceStore
    * @param ctx
    * @param mat
    * @return
    */
  def migrateReservations(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _])(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {

    logger.info("Starting reservations migration to 1.6.0")

    /**
      * We're trying to find if we have a zookeeper store to make sure that migration will run in production environment
      * and will be skipped in a unit test.
      */
    val maybeStore: Option[ZkPersistenceStore] = {

      def findZkStore(ps: PersistenceStore[_, _, _]): Option[ZkPersistenceStore] = {
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

      findZkStore(persistenceStore)
    }

    implicit val instanceResolver: IdResolver[Instance.Id, JsValue, String, ZkId] =
      new IdResolver[Instance.Id, JsValue, String, ZkId] {
        override def toStorageId(id: Id, version: Option[OffsetDateTime]): ZkId =
          ZkId(category, id.idString, version)

        override val category: String = "instance"

        override def fromStorageId(key: ZkId): Id = Instance.Id(key.id)

        override val hasVersions: Boolean = false

        override def version(v: JsValue): OffsetDateTime = OffsetDateTime.MIN
      }

    implicit val instanceJsonUnmarshaller: Unmarshaller[ZkSerialized, JsValue] =
      Unmarshaller.strict {
        case ZkSerialized(byteString) =>
          Json.parse(byteString.utf8String)
      }

    import Reservation.reservationFormat
    import Instance.instanceJsonReads

    maybeStore.map { store =>
      instanceRepository
        .ids()
        .mapAsync(1) { instanceId =>
          store.get(instanceId)
        }
        .mapConcat {
          case Some(jsValue) =>
            val instance = jsValue.as[Instance]
            //prior to 1.6.0, only PVs are supported only with apps,
            // therefore reservation can only appear in app instances,
            // and in such of apps, taskMap has only one KV pair.
            //
            // We still use .headOption here to handle the case with empty reservation
            val maybeReservationJson = (jsValue \ "tasksMap" \\ "reservation").headOption

            maybeReservationJson.map { reservationJson =>
              reservationJson.as[Reservation] -> instance :: Nil
            } getOrElse {
              Nil
            }

          case _ => Nil
        }
        .mapAsync(1) {
          case (reservation, instance) =>
            val updatedInstance = instance.copy(reservation = Some(reservation))
            instanceRepository.store(updatedInstance)
        }
        .runWith(Sink.ignore)
    } getOrElse {
      Future.successful(Done)
    }
  }
}
