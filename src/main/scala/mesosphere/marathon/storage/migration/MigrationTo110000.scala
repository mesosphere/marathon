package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}

class MigrationTo110000(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] =
    async {
      logger.info("Starting migration to 1.10.000")
      await(MigrationTo110000.migratePods(persistenceStore))
    }

}

object MigrationTo110000 {
  val migrationVersion = StorageVersions(110, 0)

  private[migration] val podMigratingFlow = Flow[(raml.Pod, Option[OffsetDateTime])].map {
    case (podRaml, version) =>
      val migratedPod = podRaml.copy(legacySharedCgroups = Some(true))

      migratedPod -> version
  }

  /**
    * Loads all pod definitions from store and sets the boolean "legacySharedCgroups" to true
    *
    * @param persistenceStore The ZooKeeper storage.
    * @return Successful future when done.
    */
  def migratePods(
      persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]
  )(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    ServiceMigration.migratePodVersions(migrationVersion, persistenceStore, podMigratingFlow)
  }

}
