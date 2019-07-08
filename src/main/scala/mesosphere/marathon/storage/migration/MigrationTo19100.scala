package mesosphere.marathon
package storage.migration

import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.ZkSerialized
import mesosphere.marathon.state.{AppDefinition, PathId, Timestamp}
import mesosphere.marathon.storage.repository.{AppRepository, PodRepository}
import mesosphere.marathon.storage.store.ZkStoreSerialization
import play.api.libs.json.Json

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}

class MigrationTo19100(
    defaultMesosRole: String,
    appRepository: AppRepository,
    podRepository: PodRepository,
    persistenceStore: PersistenceStore[_, _, _]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = async {
    logger.info("Starting migration to 1.9.100")
    await(MigrationTo19100.migrateApps(defaultMesosRole, persistenceStore, appRepository))
    await(MigrationTo19100.migratePods(defaultMesosRole, persistenceStore, podRepository))
  }
}

object MigrationTo19100 extends MaybeStore with StrictLogging {

  /**
    * Loads all app definition from store and sets the role to Marathon's default role.
    *
    * @param defaultMesosRole The Mesos role define by [[MarathonConf.mesosRole]].
    * @param persistenceStore The ZooKeeper storage.
    * @param appRepository The app repository is required to load all app ids.
    * @return Successful future when done.
    */
  def migrateApps(defaultMesosRole: String, persistenceStore: PersistenceStore[_, _, _], appRepository: AppRepository)(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    implicit val appProtosUnmarshaller: Unmarshaller[ZkSerialized, Protos.ServiceDefinition] =
      Unmarshaller.strict {
        case ZkSerialized(byteString) => Protos.ServiceDefinition.PARSER.parseFrom(byteString.toArray)
      }

    implicit val appProtosMarshaller: Marshaller[Protos.ServiceDefinition, ZkSerialized] =
      Marshaller.opaque(appProtos => ZkSerialized(ByteString(appProtos.toByteArray)))

    implicit val appIdResolver =
      new ZkStoreSerialization.ZkPathIdResolver[Protos.ServiceDefinition]("apps", true, AppDefinition.versionInfoFrom(_).version.toOffsetDateTime)

    val countingSink: Sink[Done, NotUsed] = Sink.fold[Int, Done](0) { case (count, Done) => count + 1 }
      .mapMaterializedValue { f =>
        f.map(i => logger.info(s"$i apps migrated to 1.9.100"))
        NotUsed
      }

    maybeStore(persistenceStore).map { store =>
//      appRepository
//        .ids()
//        .flatMapConcat { appId => store.versions(appId).map(v => (appId, v)) }
//        .mapAsync(Migration.maxConcurrency) { case (appId: PathId, v: OffsetDateTime) => store.get(appId, v) }
//        .collect { case Some(appProtos) if !appProtos.hasRole => appProtos }
//        .map { appProtos =>
//          logger.info("  Migrate App(" + appProtos.getId + ") to role '" + defaultMesosRole + "', (Version: " + appProtos.getVersion + ")")
//
//          // TODO: check for slave_public
//          appProtos.toBuilder.setRole(defaultMesosRole).build()
//        }
//        .mapAsync(Migration.maxConcurrency) { appProtos =>
//          store.store(PathId(appProtos.getId), appProtos, appIdResolver.version(appProtos))
//        }
//        .alsoTo(countingSink)
//        .runWith(Sink.ignore)
      appRepository
        .ids()
        .mapAsync(Migration.maxConcurrency) { case appId: PathId => store.get(appId) }
        .collect { case Some(appProtos) if !appProtos.hasRole => appProtos }
        .map { appProtos =>
          logger.info("  Migrate App(" + appProtos.getId + ") to role '" + defaultMesosRole + "', (Version: " + appProtos.getVersion + ")")

          // TODO: check for slave_public
          appProtos.toBuilder.setRole(defaultMesosRole).build()
        }
        .mapAsync(Migration.maxConcurrency) { appProtos =>
          store.store(PathId(appProtos.getId), appProtos)
        }
        .alsoTo(countingSink)
        .runWith(Sink.ignore)

    }.getOrElse {
      Future.successful(Done)
    }
  }

  def migratePods(defaultMesosRole: String, persistenceStore: PersistenceStore[_, _, _], podRepository: PodRepository)(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {

    implicit val podIdResolver =
      new ZkStoreSerialization.ZkPathIdResolver[raml.Pod]("pods", true, _.version.getOrElse(Timestamp.now().toOffsetDateTime))

    implicit val podJsonUnmarshaller: Unmarshaller[ZkSerialized, raml.Pod] =
      Unmarshaller.strict {
        case ZkSerialized(byteString) => Json.parse(byteString.utf8String).as[raml.Pod]
      }

    implicit val podRamlMarshaller: Marshaller[raml.Pod, ZkSerialized] =
      Marshaller.opaque { podRaml =>
        ZkSerialized(ByteString(Json.stringify(Json.toJson(podRaml)), StandardCharsets.UTF_8.name()))
      }

    val countingSink: Sink[Done, NotUsed] = Sink.fold[Int, Done](0) { case (count, Done) => count + 1 }
      .mapMaterializedValue { f =>
        f.map(i => logger.info(s"$i pods migrated to 1.9.100"))
        NotUsed
      }

    maybeStore(persistenceStore).map { store =>
      podRepository
        .ids()
        .mapAsync(Migration.maxConcurrency) { podId => store.get(podId) }
        .collect { case Some(podRaml) if !podRaml.role.isDefined => podRaml }
        .map { podRaml =>
          logger.info("  Migrate Pod(" + podRaml.id + ") to role '" + defaultMesosRole + "', (Version: " + podRaml.version + ")")

          // TODO: check for slave_public
          podRaml.copy(role = Some(defaultMesosRole))
        }
        .mapAsync(Migration.maxConcurrency) { podRaml =>
          store.store(PathId(podRaml.id), podRaml)
        }
        .alsoTo(countingSink)
        .runWith(Sink.ignore)
    }.getOrElse {
      Future.successful(Done)
    }
  }
}
