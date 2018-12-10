package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.{Done, NotUsed}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.storage.store.impl.cache.{LazyCachingPersistenceStore, LazyVersionCachingPersistentStore, LoadTimeCachingPersistenceStore}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkPersistenceStore, ZkSerialized}
import mesosphere.marathon.state.Instance
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json.{JsValue, Json}

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
trait InstanceMigration extends MaybeStore with StrictLogging {

  /**
    * This function traverses all instances in ZK and passes them through the migration flow before saving the updates.
    */
  def migrateInstances(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _])(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {

    val countingSink: Sink[Done, NotUsed] = Sink.fold[Int, Done](0) { case (count, Done) => count + 1 }
      .mapMaterializedValue { f =>
        f.map(i => logger.info(s"$i instances migrated"))
        NotUsed
      }

    maybeStore(persistenceStore).map { store =>
      instanceRepository
        .ids()
        .mapAsync(1) { instanceId =>
          store.get[Id, JsValue](instanceId)
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

  val migrationFlow: Flow[JsValue, Instance, NotUsed]
}