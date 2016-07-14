package mesosphere.marathon.core.storage.repository.impl

import akka.Done
import mesosphere.marathon.core.storage.PersistenceStore
import mesosphere.marathon.core.storage.impl.memory.{ Identity, InMemoryStoreSerialization, RamId }
import mesosphere.marathon.core.storage.impl.zk.{ ZkId, ZkSerialized, ZkStoreSerialization }
import mesosphere.marathon.core.storage.repository.{ AppRepository, VersionedRepository }
import mesosphere.marathon.state.{ AppDefinition, PathId }

import scala.concurrent.Future

class AppZkRepository(
  persistenceStore: PersistenceStore[ZkId, String, ZkSerialized],
  maxVersions: Int)
    extends VersionedRepository[PathId, AppDefinition, ZkId, String, ZkSerialized](persistenceStore)(
      ZkStoreSerialization.appDefResolver(maxVersions),
      ZkStoreSerialization.appDefMarshaller,
      ZkStoreSerialization.appDefUnmarshaller
    ) with AppRepository {

  override def store(appDef: AppDefinition): Future[Done] = store(appDef.id, appDef)
}

class AppInMemRepository(
  persistenceStore: PersistenceStore[RamId, String, Identity],
  maxVersions: Int)
    extends VersionedRepository[PathId, AppDefinition, RamId, String, Identity](persistenceStore)(
      InMemoryStoreSerialization.appDefResolver(maxVersions),
      InMemoryStoreSerialization.marshaller,
      InMemoryStoreSerialization.unmarshaller
    ) with AppRepository {

  override def store(appDef: AppDefinition): Future[Done] = store(appDef.id, appDef)
}
