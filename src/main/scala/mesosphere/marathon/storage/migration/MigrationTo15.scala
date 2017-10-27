package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos._
import mesosphere.marathon.api.v2.{ AppNormalization, AppHelpers }
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.{ AppDefinition, PathId, RootGroup }
import mesosphere.marathon.storage.repository.GroupRepository

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }

case class MigrationTo15(
    migration: Migration)(implicit
  executionContext: ExecutionContext,
    materializer: Materializer) extends StrictLogging {

  import MigrationTo15._

  @SuppressWarnings(Array("all")) // async/await
  def migrate(): Future[Done] = async {
    implicit val env = Environment(sys.env)
    implicit val appNormalization = appNormalizer(
      migration.availableFeatures, migration.defaultNetworkName, migration.mesosBridgeName)
    val deleteSubscribersFuture = deleteEventSubscribers(migration.persistenceStore)
    val summary = await(migrateGroups(migration.serviceDefinitionRepo, migration.groupRepository))
    await(deleteSubscribersFuture)
    logger.info(s"Migrated $summary to 1.5")
    Done
  }
}

private[migration] object MigrationTo15 {
  val DefaultNetworkNameForMigratedApps = "MIGRATION_1_5_0_MARATHON_DEFAULT_NETWORK_NAME"
  val MigrationFailedMissingNetworkEnvVar =
    "failed to migrate service because no default-network-name has been configured and" +
      s" environment variable $DefaultNetworkNameForMigratedApps is not set"

  case class MigratedRoot(root: RootGroup, apps: Seq[AppDefinition]) {
    def store(groupRepository: GroupRepository)(implicit ec: ExecutionContext): Future[MigratedRoot] =
      groupRepository.storeRoot(root, apps, Nil, Nil, Nil).map(_ => MigratedRoot.this)
  }

  case class Environment(vars: Map[String, String])

  def appNormalizer(enabledFeatures: Set[String], networkName: Option[String], mesosBridgeName: String)(
    implicit
    env: Environment): Normalization[raml.App] = {
    val mbn = mesosBridgeName
    // lazily evaluate the special environment variable and configured network name: we might never need them, and in
    // that case we don't want to abort migration (because there's no reason to).
    AppHelpers.appNormalization(
      enabledFeatures, new AppNormalization.Config {
      override def defaultNetworkName: Option[String] =
        env.vars.get(DefaultNetworkNameForMigratedApps)
          .orElse(networkName)
          .orElse(throw MigrationCancelledException(
            "Migration cancelled due to misconfiguration",
            SerializationFailedException(MigrationFailedMissingNetworkEnvVar)))
      override def mesosBridgeName =
        mbn
    })
  }

  /**
    * for each root version (+ current) load all apps from the service-definition-repository, migrate them,
    * then save changes for each root (and all of its apps) via the group-repository API.
    */
  def migrateGroups(
    serviceRepository: ServiceDefinitionRepository,
    groupRepository: GroupRepository)(implicit
    appNormalizer: Normalization[raml.App],
    ec: ExecutionContext,
    mat: Materializer): Future[(String, Int)] =

    groupRepository.rootVersions()
      .via(loadRootsFlow(groupRepository))
      .mapAsync[MigratedRoot](1)(migrateRoot(_, serviceRepository, groupRepository)) // store roots one at a time
      .runWith(summarizeMigratedRootsSink)
      .map { "root + app versions" -> _ }

  /**
    * load roots from the group repository, the flow always ends with the current root
    */
  def loadRootsFlow(groupRepository: GroupRepository) = Flow[OffsetDateTime].mapAsync(Migration.maxConcurrency) { version =>
    groupRepository.rootVersion(version)
  }.collect {
    case Some(root) => root
  }.concat {
    // ensure that the current root is written last. otherwise, after migration completes the resulting"current" root
    // may actually point to an older version (has to do with how the group repository stores roots and automatically
    // adjusts the value of "current")
    Source.fromFuture(groupRepository.root())
  }

  /**
    * migrate a root group by migrating all transitive apps and then storing the updated root group
    */
  def migrateRoot(
    root: RootGroup,
    serviceRepository: ServiceDefinitionRepository,
    groupRepository: GroupRepository)(implicit
    appNormalizer: Normalization[raml.App],
    ec: ExecutionContext,
    mat: Materializer): Future[MigratedRoot] = {

    val appIds: Seq[(PathId, OffsetDateTime)] = root.transitiveApps.map { app =>
      app.id -> app.version.toOffsetDateTime
    }(collection.breakOut)

    serviceRepository.getVersions(appIds).via(migrateServiceFlow).runWith(Sink.seq)
      .flatMap(MigratedRoot(root, _).store(groupRepository))
  }

  /**
    * migrate service definitions, first by converting from protobuf to RAML and then converting to the model API
    */
  def migrateServiceFlow(implicit appNormalizer: Normalization[raml.App]) = Flow[ServiceDefinition].map { service =>
    import Normalization._
    val rawRaml = Raml.toRaml(service)
    val normalizedApp = rawRaml.normalize
    val appDef = normalizedApp.fromRaml
    // fixup version since it's intentionally lost in the conversion from App to AppDefinition
    appDef.copy(versionInfo = AppDefinition.versionInfoFrom(service))
  }

  def summarizeMigratedRootsSink = Flow[MigratedRoot].toMat(Sink.fold(0) {
    case (acc, migratedRoot) =>
      acc + migratedRoot.apps.size + 1 // number of apps migrated + 1 for the root
  })(Keep.right)

  def deleteEventSubscribers[K, C, S](store: PersistenceStore[K, C, S]): Future[Done] = {
    store match {
      case zk: ZkPersistenceStore =>
        zk.client.delete("/event-subscribers").map(_ => Done)(ExecutionContexts.callerThread)
      case _ =>
        Future.successful(Done)
    }
  }
}
