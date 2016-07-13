package mesosphere.marathon.core.storage.repository.impl

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.impl.memory.{ Identity, RamId }
import mesosphere.marathon.core.storage.impl.zk.{ ZkId, ZkSerialized }
import mesosphere.marathon.core.storage.repository.AppRepository
import mesosphere.marathon.core.storage.{ IdResolver, PersistenceStore }
import mesosphere.marathon.state.{ AppDefinition, PathId }

import mesosphere.marathon.core.storage.impl.memory.InMemoryStoreSerialization._
import mesosphere.marathon.core.storage.impl.zk.ZkStoreSerialization._

import scala.concurrent.Future

abstract class BaseAppRepository[K, C, S](store: PersistenceStore[K, C, S], maxVersions: Int)(implicit
  idResolver: IdResolver[PathId, AppDefinition, C, K],
    marshaller: Marshaller[AppDefinition, S],
    unmarshaller: Unmarshaller[S, AppDefinition]) extends AppRepository {
  private val MaxParallelGets = 8

  override def allPathIds(): Source[PathId, NotUsed] = store.ids[PathId, AppDefinition]()

  override def currentVersion(appId: PathId): Future[Option[AppDefinition]] = store.get(appId)

  override def listVersions(appId: PathId): Source[OffsetDateTime, NotUsed] = store.versions(appId)

  override def expunge(appId: PathId): Future[Done] = store.deleteAll(appId)

  override def app(appId: PathId, version: OffsetDateTime): Future[Option[AppDefinition]] = store.get(appId, version)

  override def store(appDef: AppDefinition): Future[Done] = store.store(appDef.id, appDef)

  override def apps(): Source[AppDefinition, NotUsed] =
    allPathIds().mapAsync(MaxParallelGets)(store.get(_)).filter(_.isDefined).map(_.get)
}

class AppZkRepository(store: PersistenceStore[ZkId, String, ZkSerialized], maxVersions: Int)
  extends BaseAppRepository(store, maxVersions)

class AppInMemRepository(store: PersistenceStore[RamId, String, Identity], maxVersions: Int)
  extends BaseAppRepository(store, maxVersions)

