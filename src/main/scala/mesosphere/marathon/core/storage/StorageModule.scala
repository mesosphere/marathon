package mesosphere.marathon.core.storage

import akka.actor.Scheduler
import akka.stream.Materializer
import com.typesafe.config.Config
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.storage.repository.AppRepository
import mesosphere.marathon.core.storage.repository.impl.{AppInMemRepository, AppZkRepository}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{AppDefinition, AppEntityRepository}

import scala.concurrent.ExecutionContext

/**
  * Provides the repositories for all persistable entities.
  */
trait StorageModule {
  def appRepository: AppRepository
}

object StorageModule {
  def apply(conf: MarathonConf, metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
            scheduler: Scheduler) : StorageModule =
    apply(StorageConfig(conf))(metrics, mat, ctx, scheduler)

  def apply(config: Config, metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
            scheduler: Scheduler): StorageModule =
    apply(StorageConfig(config))(metrics, mat, ctx, scheduler)

  def apply(config: StorageConfig)(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
            scheduler: Scheduler): StorageModule = {

    config match {
      case l:LegacyStorageConfig =>
        val appRepository = new AppEntityRepository(l.entityStore("app:", () => AppDefinition.apply()),
          Some(l.maxVersions),
          metrics
        )
        StorageModuleImpl(appRepository)
      case zk:NewZk =>
        val store = zk.store(metrics, mat, ctx, scheduler)
        val appRepository = new AppZkRepository(store, zk.maxVersions)
        StorageModuleImpl(appRepository)
      case mem:NewInMem =>
        val store = mem.store(metrics, mat, ctx, scheduler)
        val appRepository = new AppInMemRepository(store, mem.maxVersions)
        StorageModuleImpl(appRepository)
    }
  }
}

private[storage] case class StorageModuleImpl(appRepository: AppRepository) extends StorageModule