package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import mesosphere.marathon.Protos.ServiceDefinition
import mesosphere.marathon.core.storage.repository.ReadOnlyVersionedRepository
import mesosphere.marathon.core.storage.repository.impl.PersistenceStoreVersionedRepository
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.core.storage.store.impl.memory.{ Identity, RamId }
import mesosphere.marathon.core.storage.store.impl.zk.{ ZkId, ZkSerialized }
import mesosphere.marathon.state.PathId
import mesosphere.marathon.storage.store.{ InMemoryStoreSerialization, ZkStoreSerialization }

trait ServiceDefinitionRepository extends ReadOnlyVersionedRepository[PathId, ServiceDefinition]

private[storage] object ServiceDefinitionRepository {

  import PathId._

  implicit val memServiceDefResolver: IdResolver[PathId, ServiceDefinition, String, RamId] =
    new InMemoryStoreSerialization.InMemPathIdResolver[ServiceDefinition](
      "app", true, v => OffsetDateTime.parse(v.getVersion))

  implicit val zkServiceDefResolver: IdResolver[PathId, ServiceDefinition, String, ZkId] =
    new ZkStoreSerialization.ZkPathIdResolver[ServiceDefinition]("apps", true, v => OffsetDateTime.parse(v.getVersion))

  private[this] class ServiceDefinitionRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
    ir: IdResolver[PathId, ServiceDefinition, C, K],
    marshaller: Marshaller[ServiceDefinition, S],
    unmarshaller: Unmarshaller[S, ServiceDefinition]) extends PersistenceStoreVersionedRepository[PathId, ServiceDefinition, K, C, S](
    persistenceStore, _.getId.toPath, v => OffsetDateTime.parse(v.getVersion)
  )(ir, marshaller, unmarshaller) with ServiceDefinitionRepository

  def inMemRepository(
    persistenceStore: PersistenceStore[RamId, String, Identity]): ServiceDefinitionRepository = {

    // not needed for now
    implicit val memMarshaller: Marshaller[ServiceDefinition, Identity] = Marshaller[ServiceDefinition, Identity] { _ =>
      throw new UnsupportedOperationException("marshalling is not supported here")
    }

    implicit val memUnmarshaller: Unmarshaller[Identity, ServiceDefinition] =
      InMemoryStoreSerialization.unmarshaller[ServiceDefinition]

    new ServiceDefinitionRepositoryImpl(persistenceStore)
  }

  def zkRepository(
    persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]): ServiceDefinitionRepository = {

    // not needed for now
    implicit val zkMarshaller: Marshaller[ServiceDefinition, ZkSerialized] = Marshaller[ServiceDefinition, ZkSerialized] { _ =>
      throw new UnsupportedOperationException("marshalling is not supported here")
    }

    implicit val zkUnmarshaller: Unmarshaller[ZkSerialized, ServiceDefinition] = Unmarshaller.strict {
      case ZkSerialized(byteString) => ServiceDefinition.parseFrom(byteString.toArray)
    }

    new ServiceDefinitionRepositoryImpl(persistenceStore)
  }
}

