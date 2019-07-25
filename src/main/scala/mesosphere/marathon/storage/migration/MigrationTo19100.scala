package mesosphere.marathon
package storage.migration

import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance.{AgentInfo, Id, InstanceState}
import mesosphere.marathon.core.instance.Reservation
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.{InstanceRepository, StoredGroup, StoredGroupRepositoryImpl}
import mesosphere.marathon.storage.store.ZkStoreSerialization
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}

class MigrationTo19100(
    defaultMesosRole: Role,
    instanceRepository: InstanceRepository,
    persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = async {
    logger.info("Starting migration to 1.9.100")
    await(MigrationTo19100.migrateApps(defaultMesosRole, persistenceStore))
    await(MigrationTo19100.migratePods(defaultMesosRole, persistenceStore))
    await(MigrationTo19100.migrateGroups(persistenceStore))
    await(InstanceMigration.migrateInstances(instanceRepository, persistenceStore, instanceMigrationFlow))
  }

  /**
    * Read format for old instance without reservation id.
    */
  val instanceJsonReads18200: Reads[Instance] = {
    import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
    import mesosphere.marathon.core.instance.Instance.tasksMapFormat

    (
      (__ \ "instanceId").read[Id] ~
      (__ \ "agentInfo").read[AgentInfo] ~
      (__ \ "tasksMap").read[Map[Task.Id, Task]] ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[InstanceState] ~
      (__ \ "reservation").readNullable[Reservation] ~
      (__ \ "role").readNullable[String]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, reservation, persistedRole) =>
        logger.info(s"Migrate $instanceId")

        val role = persistedRole.orElse(Some(defaultMesosRole))

        new Instance(instanceId, Some(agentInfo), state, tasksMap, runSpecVersion, reservation, role)
      }
  }

  /**
    * Extract instance from old format
    *
    * @param jsValue The instance as JSON.
    * @return The parsed instance.
    */
  def extractInstanceFromJson(jsValue: JsValue): Instance = jsValue.as[Instance](instanceJsonReads18200)

  val instanceMigrationFlow = Flow[JsValue]
    .filter { jsValue =>
      // Only migrate instances that don't have a role
      (jsValue \ "role").isEmpty
    }
    .map(extractInstanceFromJson)
}

object MigrationTo19100 extends MaybeStore with StrictLogging {

  /**
    * Set the default role on the app.
    *
    * @param appProtos The raw app from store.
    * @param optVersion The optional version of the app definition.
    * @param defaultMesosRole The default Mesos role to use.
    * @return The update app definition.
    */
  def migrateApp(appProtos: Protos.ServiceDefinition, optVersion: Option[OffsetDateTime], defaultMesosRole: Role): (Protos.ServiceDefinition, Option[OffsetDateTime]) = {
    logger.info(s"Migrate App(${appProtos.getId}) with store version $optVersion to role '$defaultMesosRole' (AppVersion: ${appProtos.getVersion})")

    val newAppProtos = appProtos.toBuilder.setRole(defaultMesosRole).build()

    (newAppProtos, optVersion)
  }

  /**
    * Set the default role on the pod.
    *
    * @param podRaml The raw pod from store.
    * @param optVersion The optional version of the pod definition.
    * @param defaultMesosRole The default Mesos role to use.
    * @return The update pod definition.
    */
  def migratePod(podRaml: raml.Pod, optVersion: Option[OffsetDateTime], defaultMesosRole: Role): (raml.Pod, Option[OffsetDateTime]) = {
    logger.info(s"Migrate Pod(${podRaml.id}) with store version $optVersion to role '$defaultMesosRole', (Version: ${podRaml.version})")

    val newPod = podRaml.copy(role = Some(defaultMesosRole))

    (newPod, optVersion)
  }

  /**
    * Recursively sets the enforce role parameter to `false` for all groups.
    *
    * @param group The current group.
    * @return The update group with all it's children updated.
    */
  def migrateGroup(group: StoredGroup): StoredGroup = {
    // This is not tail-recursive. We might run into a stackoverflow.
    group.copy(enforceRole = Some(false), storedGroups = group.storedGroups.map(migrateGroup))
  }

  /**
    * Loads all app definitions from store and sets the role to Marathon's default role.
    *
    * @param defaultMesosRole The Mesos role define by [[MarathonConf.mesosRole]].
    * @param persistenceStore The ZooKeeper storage.
    * @return Successful future when done.
    */
  def migrateApps(defaultMesosRole: Role, persistenceStore: PersistenceStore[ZkId, String, ZkSerialized])(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    implicit val appProtosUnmarshaller: Unmarshaller[ZkSerialized, Protos.ServiceDefinition] =
      Unmarshaller.strict {
        case ZkSerialized(byteString) => Protos.ServiceDefinition.parseFrom(byteString.toArray)
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

    maybeStore(persistenceStore).map{ zkStore =>
      zkStore
        .ids()
        .flatMapConcat(appId => zkStore.versions(appId).map(v => (appId, Some(v))) ++ Source.single((appId, Option.empty[OffsetDateTime])))
        .mapAsync(Migration.maxConcurrency) {
          case (appId, Some(version)) => zkStore.get(appId, version).map(app => (app, Some(version)))
          case (appId, None) => zkStore.get(appId).map(app => (app, Option.empty[OffsetDateTime]))
        }
        .collect{ case (Some(appProtos), optVersion) if !appProtos.hasRole => (appProtos, optVersion) }
        .map{
          case (appProtos, optVersion) => migrateApp(appProtos, optVersion, defaultMesosRole)
        }
        .mapAsync(Migration.maxConcurrency) {
          case (appProtos, Some(version)) => zkStore.store(PathId(appProtos.getId), appProtos, version)
          case (appProtos, None) => zkStore.store(PathId(appProtos.getId), appProtos)
        }
        .alsoTo(countingSink)
        .runWith(Sink.ignore)
    }.getOrElse {
      Future.successful(Done)
    }
  }

  /**
    * Loads all pod definitions from store and sets the role to Marathon's default role.
    *
    * @param defaultMesosRole The Mesos role define by [[MarathonConf.mesosRole]].
    * @param persistenceStore The ZooKeeper storage.
    * @return Successful future when done.
    */
  def migratePods(defaultMesosRole: Role, persistenceStore: PersistenceStore[ZkId, String, ZkSerialized])(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {

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

    maybeStore(persistenceStore).map{ zkStore =>
      zkStore
        .ids()
        .flatMapConcat(podId => zkStore.versions(podId).map(v => (podId, Some(v))) ++ Source.single((podId, Option.empty[OffsetDateTime])))
        .mapAsync(Migration.maxConcurrency) {
          case (podId, Some(version)) => zkStore.get(podId, version).map(pod => (pod, Some(version)))
          case (podId, None) => zkStore.get(podId).map(pod => (pod, Option.empty[OffsetDateTime]))
        }
        .collect{ case (Some(podRaml), optVersion) if podRaml.role.isEmpty => (podRaml, optVersion) }
        .map{
          case (podRaml, optVersion) => migratePod(podRaml, optVersion, defaultMesosRole)
        }
        .mapAsync(Migration.maxConcurrency) {
          case (podRaml, Some(version)) => zkStore.store(PathId(podRaml.id), podRaml, version)
          case (podRaml, None) => zkStore.store(PathId(podRaml.id), podRaml)
        }
        .alsoTo(countingSink)
        .runWith(Sink.ignore)
    }.getOrElse {
      Future.successful(Done)
    }
  }

  def migrateGroups(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized])(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    import StoredGroupRepositoryImpl.RootId
    import ZkStoreSerialization.{groupIdResolver, groupMarshaller, groupUnmarshaller}

    val countingSink: Sink[Done, NotUsed] = Sink.fold[Int, Done](0) { case (count, Done) => count + 1 }
      .mapMaterializedValue { f =>
        f.map(i => logger.info(s"$i pods migrated to 1.9.100"))
        NotUsed
      }

    maybeStore(persistenceStore).map{ zkStore =>
      zkStore
        .versions(RootId).map(Some(_)).concat(Source.single(Option.empty[OffsetDateTime]))
        .mapAsync(Migration.maxConcurrency) {
          case Some(rootGroupVersion) => zkStore.get(RootId, rootGroupVersion).map(group => (group, Some(rootGroupVersion)))
          case None => zkStore.get(RootId).map(group => (group, None))
        }
        .collect{ case (Some(group), optVersion) if group.enforceRole.isEmpty => (group, optVersion) }
        .map{
          case (rootGroup, optVersion) => (migrateGroup(rootGroup), optVersion)
        }
        .mapAsync(Migration.maxConcurrency) {
          case (rootGroup, Some(version)) => zkStore.store(RootId, rootGroup, version)
          case (rootGroup, None) => zkStore.store(RootId, rootGroup)
        }
        .alsoTo(countingSink)
        .runWith(Sink.ignore)
    }.getOrElse {
      Future.successful(Done)
    }
  }
}
