package mesosphere.marathon.storage.migration.legacy.legacy

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, Timestamp }
import mesosphere.marathon.storage.LegacyStorageConfig
import mesosphere.marathon.storage.repository.{ AppRepository, GroupRepository, PodRepository }
import org.slf4j.LoggerFactory

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }

/**
  * Implements the following migration logic:
  * - Load all root versions and then store them again. Historical versions must store the historical app
  *   versions for that version of the group as well (since storeRoot would normally do this).
  *   mesosphere.marathon.state.AppDefinition.mergeFromProto
  *   will update portDefinitions from the deprecated ports and when we save them again
  *   [[mesosphere.marathon.state.AppDefinition.toProto]] will save the new definitions and remove the deprecated ports.
  * - TODO: Could we end up with apps that have historical versions that don't have the new proto? This would
  *   only really make sense if the version wasn't referenced by an app.
  */
@SuppressWarnings(Array("ClassNames"))
class MigrationTo0_16(legacyConfig: Option[LegacyStorageConfig])(implicit
  ctx: ExecutionContext,
    mat: Materializer,
    metrics: Metrics) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  @SuppressWarnings(Array("all")) // async/await
  def migrate(): Future[Unit] =
    legacyConfig.fold(Future.successful(())) { config =>
      async { // linter:ignore UnnecessaryElseBranch
        log.info("Start 0.16 migration")
        val appRepository = AppRepository.legacyRepository(config.entityStore[AppDefinition], config.maxVersions)
        val podRepository = PodRepository.legacyRepository(config.entityStore[PodDefinition], config.maxVersions)
        val groupRepository =
          GroupRepository.legacyRepository(config.entityStore[Group], config.maxVersions, appRepository, podRepository)
        implicit val groupOrdering = Ordering.by[Group, Timestamp](_.version)

        val groupVersions = await {
          groupRepository.rootVersions().mapAsync(Int.MaxValue) { version =>
            groupRepository.rootVersion(version)
          }.collect { case Some(r) => r }.runWith(Sink.seq).map(_.sorted)
        }

        val storeHistoricalApps = Future.sequence(
          groupVersions.flatMap { version =>
            version.transitiveApps.map { app =>
              appRepository.storeVersion(app)
            }
          }
        )
        val storeUpdatedVersions = Future.sequence(groupVersions.map(groupRepository.storeVersion))
        await(storeHistoricalApps)
        await(storeUpdatedVersions)

        val root = await(groupRepository.root())
        await(groupRepository.storeRoot(root, root.transitiveApps.toVector, Nil, Nil, Nil))
        log.info("Finished 0.16 migration")
        ()
      }
    }
}
