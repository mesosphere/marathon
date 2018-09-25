package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

import akka.{Done, NotUsed}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, _}
import mesosphere.marathon.state.PathId

import scala.concurrent.{ExecutionContext, Future}
import mesosphere.marathon.storage.store.ZkStoreSerialization
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import scala.async.Async.{async, await}
import scala.collection.immutable.Seq
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import mesosphere.marathon.util.toRichFuture

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

object MigrationTo161 extends StrictLogging {

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

  // copy of rawVersions from zkPersistenceStorage but with support for old dateTime formatter
  def rawVersions(id: ZkId, client: RichCuratorFramework)(implicit ec: ExecutionContext): Source[String, NotUsed] = {

    val unversioned = id.copy(version = None)
    val path = unversioned.path
    val versions =
      async {
        await(client.children(path).asTry) match {
          case Success(Children(_, _, nodes)) =>
            nodes.map { path =>
              path
            }
          case Failure(_: NoNodeException) =>
            Seq.empty
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to get versions of $id", e)
          case Failure(e) =>
            throw e
        }
      }

    Source.fromFuture(versions).mapConcat(identity)
  }

  def updateIdFlow[Payload](
    persistenceStore: PersistenceStore[ZkId, String, ZkSerialized],
    idResolver: IdResolver[PathId, Payload, String, ZkId],
    client: RichCuratorFramework)(implicit ec: ExecutionContext, mat: Materializer): Flow[PathId, Done, NotUsed] = {

    Flow[PathId]
      .mapAsync(1) { pathId: PathId => //get the raw versions (as stored in zk)
        val zkId = idResolver.toStorageId(pathId, None)
        val versions = rawVersions(zkId, client)
          .map(v => pathId -> v)
          .runWith(Sink.seq)
        versions
      }
      .mapConcat(identity) // path -> version
      .collect { // if the version is not correct, include it for migration, skip otherwise
        case (pathId, version) if Try(ZkId.DateFormat.parse(version)).isFailure =>
          logger.info(s"version $version of app/pod $pathId is going to be migrated")
          pathId -> OffsetDateTime.parse(version)
      }
      .mapAsync(1) {
        case (pathId, version) =>
          val zkId = idResolver.toStorageId(pathId, Some(version))
          val nodeId = oldZkIdPath(zkId)
          val znodeData = client.data(nodeId).map(_.data)
          znodeData.map(bytes => zkId -> bytes)
      } // payload fetched
      .mapAsync(1) {
        case (zkId, payload) =>
          logger.info(s"copying ${oldZkIdPath(zkId)} into ${zkId.path}")
          client.create(zkId.path, Some(payload))
            .map(_ => zkId :: Nil)
            .recover {
              case _: NodeExistsException => // migration might be interruped and restarted, we should skip the creation
                zkId :: Nil
            }
      } // new node created
      .mapConcat(identity)
      .mapAsync(1) { zkId =>
        logger.info(s"deleting the old node: ${oldZkIdPath(zkId)}")
        client.delete(oldZkIdPath(zkId)).map(_ => Done)
      } //old node deleted
  }
}