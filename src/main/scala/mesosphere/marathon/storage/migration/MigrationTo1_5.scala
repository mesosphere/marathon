package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos._
import mesosphere.marathon.api.v2.{ AppNormalization, AppsResource }
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.storage.repository.GroupRepository

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }

case class MigrationTo1_5(
    migration: Migration)(implicit
  executionContext: ExecutionContext,
    materializer: Materializer) extends StrictLogging {

  import MigrationTo1_5._

  @SuppressWarnings(Array("all")) // async/await
  def migrate(): Future[Done] = async {
    implicit val appNormalizer: Normalization[raml.App] = AppsResource.appNormalization(AppsResource.NormalizationConfig(
      migration.availableFeatures,
      new AppNormalization.Config {
        override def defaultNetworkName: Option[String] =
          sys.env.get(DefaultNetworkNameForMigratedApps).orElse(migration.defaultNetworkName).orElse(
            throw SerializationFailedException(
              "failed to migrate service because no default-network-name has been configured and" +
                s" environment variable $DefaultNetworkNameForMigratedApps is not set")
          )
      }
    ))
    val summary = await(migrateGroups(migration.serviceDefinitionRepo, migration.groupRepository))
    logger.info(s"Migrated $summary to 1.5")
    Done
  }

  /**
    * for each root version (+ current) load all apps from the service-definition-repository, migrate them,
    * then save changes for each root (and all of its apps) via the group-repository API.
    */
  @SuppressWarnings(Array("all")) // async/await
  def migrateGroups(serviceRepository: ServiceDefinitionRepository, groupRepository: GroupRepository)(implicit appNormalizer: Normalization[raml.App]): Future[(String, Int)] =
    async {
      val result: Future[(String, Int)] = groupRepository.rootVersions().mapAsync(Int.MaxValue) { version =>
        groupRepository.rootVersion(version)
      }.collect {
        case Some(root) => root
      }.concat {
        Source.fromFuture(groupRepository.root())
      }.mapAsync(1) { root =>
        // store roots one at a time with current root last
        val appIds: Seq[(PathId, OffsetDateTime)] = root.transitiveApps.map { app =>
          app.id -> app.version.toOffsetDateTime
        }(collection.breakOut)
        serviceRepository.getVersions(appIds).map(migrateApp).runWith(Sink.seq).map { apps =>
          groupRepository.storeRoot(root, apps, Nil, Nil, Nil).map(_ => apps.size)
        }
      }.flatMapConcat(f => Source.fromFuture(f)).runFold(0) {
        case (acc, apps) => acc + apps + 1
      }.map("root + app versions" -> _)
      await(result)
    }

  def migrateApp(service: ServiceDefinition)(implicit appNormalizer: Normalization[raml.App]): AppDefinition = {
    import Normalization._
    val rawRaml = Raml.toRaml(service)
    val normalizedApp = rawRaml.normalize
    val appDef = normalizedApp.fromRaml
    // fixup version since it's intentionally lost in the conversion from App to AppDefinition
    appDef.copy(versionInfo = AppDefinition.versionInfoFrom(service))
  }
}

object MigrationTo1_5 {
  private[migration] val DefaultNetworkNameForMigratedApps = "MIGRATION_1_5_0_MARATHON_DEFAULT_NETWORK_NAME"
}
