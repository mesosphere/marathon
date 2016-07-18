package mesosphere.marathon.core.storage

import java.util.UUID

import akka.actor.{ ActorRefFactory, Scheduler }
import akka.stream.Materializer
import com.typesafe.config.Config
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.storage.repository.{ AppRepository, TaskRepository }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, AppEntityRepository, MarathonTaskState, TaskEntityRepository }

import scala.concurrent.ExecutionContext

/**
  * Provides the repositories for all persistable entities.
  */
trait StorageModule {
  def appRepository: AppRepository
  def taskRepository: TaskRepository
}

object StorageModule {
  def apply(conf: MarathonConf, metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): StorageModule =
    apply(StorageConfig(conf))(metrics, mat, ctx, scheduler, actorRefFactory)

  def apply(config: Config)(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): StorageModule =
    apply(StorageConfig(config))

  def apply(config: StorageConfig)(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): StorageModule = {

    config match {
      case l: LegacyStorageConfig =>
        val appRepository = new AppEntityRepository(
          l.entityStore("app:", () => AppDefinition.apply()),
          Some(l.maxVersions),
          metrics
        )
        val taskRepository = new TaskEntityRepository(l.entityStore(
          TaskEntityRepository.storePrefix,
          () => MarathonTaskState(MarathonTask.newBuilder().setId(UUID.randomUUID().toString).build())), metrics)
        StorageModuleImpl(appRepository, taskRepository)
      case zk: NewZk =>
        val store = zk.store
        val appRepository = AppRepository.zkRepository(store, zk.maxVersions)
        val taskRepository = TaskRepository.zkRepository(store)
        StorageModuleImpl(appRepository, taskRepository)
      case mem: NewInMem =>
        val store = mem.store
        val appRepository = AppRepository.inMemRepository(store, mem.maxVersions)
        val taskRepository = TaskRepository.inMemRepository(store)
        StorageModuleImpl(appRepository, taskRepository)
    }
  }
}

private[storage] case class StorageModuleImpl(
  appRepository: AppRepository,
  taskRepository: TaskRepository) extends StorageModule
