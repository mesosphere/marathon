package mesosphere.marathon.core.storage.repository

import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.PersistenceStore
import mesosphere.marathon.core.storage.impl.memory.{ Identity, InMemoryStoreSerialization, RamId }
import mesosphere.marathon.core.storage.impl.zk.{ ZkId, ZkSerialized, ZkStoreSerialization }
import mesosphere.marathon.core.storage.repository.impl.AppRepositoryImpl
import mesosphere.marathon.state.{ AppDefinition, PathId }

import scala.concurrent.Future

/**
  * This repository should only be used by the group manager, otherwise inconsistencies may
  * exist where a historical group may refer to a version of an app that no longer exists.
  */
trait AppRepository {
  def ids(): Source[PathId, NotUsed]

  def get(appId: PathId): Future[Option[AppDefinition]]

  /**
    * List all of the versions of the given app
    */
  def versions(appId: PathId): Source[OffsetDateTime, NotUsed]

  /**
    * Delete all versions of the given app.
    */
  def delete(appId: PathId): Future[Done]

  /**
    * Returns the app with the supplied id and version.
    */
  def get(appId: PathId, version: OffsetDateTime): Future[Option[AppDefinition]]

  def store(id: PathId, appDef: AppDefinition): Future[Done]

  def store(id: PathId, appDef: AppDefinition, version: OffsetDateTime): Future[Done]

  /**
    * Stores the supplied app, now the current version for that apps's id.
    */
  def store(appDef: AppDefinition): Future[Done] = store(appDef.id, appDef)

  def store(appDef: AppDefinition, version: OffsetDateTime): Future[Done] =
    store(appDef.id, appDef, appDef.version.toOffsetDateTime)

  /**
    * Returns the current version for all apps.
    */
  def all(): Source[AppDefinition, NotUsed]
}

object AppRepository {
  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized], maxVersions: Int): AppRepository = {
    import ZkStoreSerialization._
    implicit def idResolver = appDefResolver(maxVersions)

    new AppRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity], maxVersions: Int): AppRepository = {
    import InMemoryStoreSerialization._
    implicit def idResolver = appDefResolver(maxVersions)
    new AppRepositoryImpl(persistenceStore)
  }
}
