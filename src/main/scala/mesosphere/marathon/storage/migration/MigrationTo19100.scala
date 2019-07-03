package mesosphere.marathon.storage.migration

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.{Done, NotUsed}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon
import mesosphere.marathon.Protos
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.state.{AppDefinition, PathId}
import mesosphere.marathon.storage.repository.{AppRepository, AppRepositoryImpl, PodRepository}
import mesosphere.marathon.storage.store.ZkStoreSerialization

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}

class MigrationTo19100(
    appRepository: AppRepository,
    podRepository: PodRepository,
    persistenceStore: PersistenceStore[_, _, _]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = async {
    logger.info("Starting migration to 1.9.100")
    await(MigrationTo19100.migrateApps(persistenceStore, appRepository))
    await(MigrationTo19100.migratePods(podRepository))
  }
}

object MigrationTo19100 extends MaybeStore with StrictLogging {

  def migrateApps(persistenceStore: PersistenceStore[_, _, _], appRepository: AppRepository)(implicit mat: Materializer): Future[Done] = {
    implicit val appProtosUnmarshaller: Unmarshaller[ZkSerialized, Protos.ServiceDefinition] =
      Unmarshaller.strict {
        case ZkSerialized(byteString) => Protos.ServiceDefinition.PARSER.parseFrom(byteString.toArray)
      }

    implicit val appProtosMarshaller: Marshaller[Protos.ServiceDefinition, ZkSerialized] =
      Marshaller.opaque(appProtos => ZkSerialized(ByteString(appProtos.toByteArray)))

    implicit val appIdResolver =
      new ZkStoreSerialization.ZkPathIdResolver[Protos.ServiceDefinition]("apps", true, AppDefinition.versionInfoFrom(_).version.toOffsetDateTime)

    maybeStore(persistenceStore).map { store =>
      appRepository
        .ids()
        .mapAsync(Migration.maxConcurrency) { appId => store.get(appId) }
        .collect { case Some(appProtos) if !appProtos.hasRole => appProtos }
        .map { appProtos =>
          appProtos.toBuilder.setRole("*").build() // TODO: use --mesos_role
        }
        .mapAsync(Migration.maxConcurrency) { appProtos =>
          store.store(PathId(appProtos.getId), appProtos)
        }
        .runWith(Sink.ignore)
    }.getOrElse {
      Future.successful(Done)
    }
  }

  def migratePods(podRepository: PodRepository): Future[Done] = ???
}
