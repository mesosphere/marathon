package mesosphere.marathon
package storage.migration

import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkPersistenceStore, ZkSerialized}
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
    persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = async {
    logger.info("Starting migration to 1.9.100")
    await(MigrationTo19100.migrateApps(defaultMesosRole, persistenceStore))
    await(MigrationTo19100.migratePods(defaultMesosRole, persistenceStore))
  }
}

object MigrationTo19100 extends MaybeStore with StrictLogging {

  /**
    * Loads all app definition from store and sets the role to Marathon's default role.
    *
    * @param defaultMesosRole The Mesos role define by [[MarathonConf.mesosRole]].
    * @param persistenceStore The ZooKeeper storage.
    * @return Successful future when done.
    */
  def migrateApps(defaultMesosRole: String, persistenceStore: PersistenceStore[ZkId, String, ZkSerialized])(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    implicit val appProtosUnmarshaller: Unmarshaller[ZkSerialized, Protos.ServiceDefinition] =
      Unmarshaller.strict {
        case ZkSerialized(byteString) => Protos.ServiceDefinition.PARSER.parseFrom(byteString.toArray)
      }

    implicit val appProtosMarshaller: Marshaller[Protos.ServiceDefinition, ZkSerialized] =
      Marshaller.opaque(appProtos => ZkSerialized(ByteString(appProtos.toByteArray)))

    implicit val appIdResolver: IdResolver[PathId, Protos.ServiceDefinition, String, ZkId] =
      new ZkStoreSerialization.ZkPathIdResolver[Protos.ServiceDefinition]("apps", true, AppDefinition.versionInfoFrom(_).version.toOffsetDateTime)

    val countingSink: Sink[Done, NotUsed] = Sink.fold[Int, Done](0) { case (count, Done) => count + 1 }
      .mapMaterializedValue { f =>
        f.map(i => logger.info(s"$i apps migrated to 1.9.100"))
        NotUsed
      }

    val zkStore:ZkPersistenceStore = maybeStore(persistenceStore).getOrElse(throw new IllegalStateException("Failed to access zkStore for migration"))

    zkStore
      .ids()
      .flatMapConcat(appId => zkStore.versions(appId).map(v => (appId, Some(v))) ++ Source.single((appId, Option.empty[OffsetDateTime])))
      .mapAsync(Migration.maxConcurrency) {
        case (appId, Some(version)) => zkStore.get(appId, version).map(app => (app, Some(version)))
        case (appId, None) => zkStore.get(appId).map(app => (app, Option.empty[OffsetDateTime]))
      }
      .collect{ case (Some(appProtos), optVersion) if !appProtos.hasRole => (appProtos, optVersion) }
      .map{
        case (appProtos, optVersion) =>
          logger.info("  Migrate App(" + appProtos.getId + ") with store version " + optVersion + " to role '" + defaultMesosRole + "', (AppVersion: " + appProtos.getVersion + ")")

          // TODO: check for slave_public
          val newAppProtos = appProtos.toBuilder.setRole(defaultMesosRole).build()

          (newAppProtos, optVersion)
      }
      .mapAsync(Migration.maxConcurrency) {
        case (appProtos, Some(version)) => zkStore.store(PathId(appProtos.getId), appProtos, version)
        case (appProtos, None) => zkStore.store(PathId(appProtos.getId), appProtos)
      }
      .alsoTo(countingSink)
      .runWith(Sink.ignore)
  }

  def migratePods(defaultMesosRole: String, persistenceStore: PersistenceStore[ZkId, String, ZkSerialized])(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {

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

    val zkStore:ZkPersistenceStore = maybeStore(persistenceStore).getOrElse(throw new IllegalStateException("Failed to access zkStore for migration"))

    zkStore
      .ids()
      .flatMapConcat(podId => zkStore.versions(podId).map(v => (podId, Some(v))) ++ Source.single((podId, Option.empty[OffsetDateTime])))
      .mapAsync(Migration.maxConcurrency) {
        case (podId, Some(version)) => zkStore.get(podId, version).map(pod => (pod, Some(version)))
        case (podId, None) => zkStore.get(podId).map(pod => (pod, Option.empty[OffsetDateTime]))
      }
      .collect{ case (Some(podRaml), optVersion) if podRaml.role.isEmpty => (podRaml, optVersion) }
      .map{
        case (podRaml, optVersion) =>
          logger.info("  Migrate Pod(" + podRaml.id + ") with store version " + optVersion + " to role '" + defaultMesosRole + "', (Version: " + podRaml.version + ")")

          // TODO: check for slave_public
          val newPod = podRaml.copy(role = Some(defaultMesosRole))

          (newPod, optVersion)
      }
      .mapAsync(Migration.maxConcurrency) {
        case (podRaml, Some(version)) => zkStore.store(PathId(podRaml.id), podRaml, version)
        case (podRaml, None) => zkStore.store(PathId(podRaml.id), podRaml)
      }
      .alsoTo(countingSink)
      .runWith(Sink.ignore)
  }
}
