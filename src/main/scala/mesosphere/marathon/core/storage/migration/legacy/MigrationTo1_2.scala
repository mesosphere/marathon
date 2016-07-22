package mesosphere.marathon.core.storage.migration.legacy

import mesosphere.marathon.core.storage.LegacyStorageConfig
import mesosphere.marathon.core.storage.repository.DeploymentRepository
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.upgrade.DeploymentPlan
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Removes all deployment version nodes from ZK
  */
class MigrationTo1_2(legacyConfig: Option[LegacyStorageConfig])(implicit ctx: ExecutionContext, metrics: Metrics) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  def migrate(): Future[Unit] =
    legacyConfig.map { config =>
      log.info("Start 1.2 migration")

      val entityStore = DeploymentRepository.legacyRepository(config.entityStore[DeploymentPlan]).store

      import mesosphere.marathon.state.VersionedEntry.isVersionKey

      entityStore.names().map(_.filter(isVersionKey)).flatMap { versionNodes =>
        versionNodes.foldLeft(Future.successful(())) { (future, versionNode) =>
          future.flatMap { _ =>
            entityStore.expunge(versionNode).map(_ => ())
          }
        }
      }.flatMap { _ =>
        log.info("Finished 1.2 migration")
        Future.successful(())
      }
    }.getOrElse(Future.successful(()))
}
