package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.time.temporal.ChronoField

import akka.{Done, NotUsed}
import akka.stream.{Attributes, Materializer}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, _}
import mesosphere.marathon.state.{AppDefinition, PathId}
import mesosphere.marathon.storage.repository.InstanceRepository
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.NoNodeException
import mesosphere.marathon.util.toRichFuture

import scala.async.Async.{async, await}
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import mesosphere.marathon.storage.store.ZkStoreSerialization

import scala.util.control.NonFatal

class MigrationTo161(persistenceStore: PersistenceStore[_, _, _]) extends MigrationStep with StrictLogging with MaybeStore {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {

    maybeStore(persistenceStore).map { zkStore =>
      for {
        _ <- MigrationTo161.migrateApps(zkStore, zkStore.client)
        _ <- MigrationTo161.migratePods(zkStore, zkStore.client)
      } yield Done
    }.getOrElse {
      logger.info("ZkPersistentStore didn't found. Skipping migration")
      Future.successful(Done)
    }

  }
}

object MigrationTo161 {

  val MAX_PARALLELISM = 8

  def oldZkIdPath(zkId: ZkId): String = {
    val ZkId(category: String, id: String, version: Option[OffsetDateTime]) = zkId
    val bucket = math.abs(id.hashCode % ZkId.HashBucketSize)

    version.fold(f"/$category/$bucket%x/$id") { v =>
      f"/$category/$bucket%x/$id/${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(v)}"
    }
  }

  def migrateApps(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized], client: RichCuratorFramework)(implicit mat: Materializer, ec: ExecutionContext): Future[Done] = {

    val resolver = ZkStoreSerialization.appDefResolver

    persistenceStore.ids()(resolver)
      .via(updateIdFlow(persistenceStore, resolver, client))
      .runWith(Sink.ignore)
  }

  def migratePods(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized], client: RichCuratorFramework)(implicit mat: Materializer, ec: ExecutionContext): Future[Done] = {

    val resolver = ZkStoreSerialization.podDefResolver

    persistenceStore.ids()(resolver)
      .via(updateIdFlow(persistenceStore, resolver, client))
      .runWith(Sink.ignore)
  }

  def updateIdFlow[Payload](
    persistenceStore: PersistenceStore[ZkId, String, ZkSerialized],
    idResolver: IdResolver[PathId, Payload, String, ZkId],
    client: RichCuratorFramework)(implicit ec: ExecutionContext, mat: Materializer): Flow[PathId, Done, NotUsed] = {

    Flow[PathId]
      .mapAsync(1) { pathId: PathId =>
        val versions = persistenceStore
          .versions(pathId)(idResolver)
          .map(v => pathId -> v)
          .runWith(Sink.seq)
        versions
      }
      .mapConcat(identity) // path -> version
      .mapAsync(1) {
        case (pathId, version) =>
          val zkId = idResolver.toStorageId(pathId, Some(version))
          val nodeId = oldZkIdPath(zkId)
          val znodeData = client.data(nodeId).map(_.data)
          znodeData.map(bytes => zkId -> bytes)
      } // payload fetched
      .mapAsync(1) {
        case (zkId, payload) =>
          client.setData(zkId.path, payload)
            .map(_ => zkId :: Nil)
            .recover {
              case NonFatal(ex) => Nil //if migration was interrupted,
            }
      } // new node created
      .mapConcat(identity)
      .mapAsync(1) { zkId =>
        client.delete(oldZkIdPath(zkId)).map(_ => Done)
      } //old node deleted
  }
}