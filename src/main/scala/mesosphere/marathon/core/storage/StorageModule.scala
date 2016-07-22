package mesosphere.marathon.core.storage

// scalastyle:off
import akka.actor.{ ActorRefFactory, Scheduler }
import akka.stream.Materializer
import com.typesafe.config.Config
import mesosphere.marathon.core.storage.migration.Migration
import mesosphere.marathon.core.storage.repository.{ AppRepository, DeploymentRepository, GroupRepository, ReadOnlyAppRepository, TaskFailureRepository, TaskRepository }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, MarathonTaskState, TaskFailure }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.util.toRichConfig

import scala.concurrent.ExecutionContext
// scalastyle:on

/**
  * Provides the repositories for all persistable entities.
  */
trait StorageModule {
  def appRepository: ReadOnlyAppRepository
  def taskRepository: TaskRepository
  def deploymentRepository: DeploymentRepository
  def taskFailureRepository: TaskFailureRepository
  def groupRepository: GroupRepository
  def migration: Migration
}

object StorageModule {
  def apply(conf: StorageConf)(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): StorageModule = {
    val currentConfig = StorageConfig(conf)
    val legacyConfig = conf.internalStoreBackend() match {
      case TwitterZk.StoreName => Some(TwitterZk(cache = true, conf))
      case MesosZk.StoreName => Some(MesosZk(cache = true, conf))
      case CuratorZk.StoreName => Some(TwitterZk(cache = true, conf))
      case InMem.StoreName => None
    }
    apply(currentConfig, legacyConfig)
  }

  def apply(config: Config)(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): StorageModule = {

    val currentConfig = StorageConfig(config)
    val legacyConfig = config.optionalConfig("legacy-migration")
      .map(StorageConfig(_)).collect { case l: LegacyStorageConfig => l }
    apply(currentConfig, legacyConfig)
  }

  def apply(
    config: StorageConfig,
    legacyConfig: Option[LegacyStorageConfig])(implicit
    metrics: Metrics,
    mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): StorageModule = {

    config match {
      case l: LegacyStorageConfig =>
        val appRepository = AppRepository.legacyRepository(l.entityStore[AppDefinition], l.maxVersions)
        val taskRepository = TaskRepository.legacyRepository(l.entityStore[MarathonTaskState])
        val deploymentRepository = DeploymentRepository.legacyRepository(l.entityStore[DeploymentPlan])
        val taskFailureRepository = TaskFailureRepository.legacyRepository(l.entityStore[TaskFailure])
        val groupRepository = GroupRepository.legacyRepository(l.entityStore[Group], l.maxVersions, appRepository)

        val migration = new Migration(legacyConfig, None, appRepository, groupRepository,
          deploymentRepository, taskRepository, taskFailureRepository)

        StorageModuleImpl(appRepository, taskRepository, deploymentRepository,
          taskFailureRepository, groupRepository, migration)
      case zk: CuratorZk =>
        val store = zk.store
        val appRepository = AppRepository.zkRepository(store, zk.maxVersions)
        val taskRepository = TaskRepository.zkRepository(store)
        val deploymentRepository = DeploymentRepository.zkRepository(store)
        val taskFailureRepository = TaskFailureRepository.zkRepository(store)
        val groupRepository = GroupRepository.zkRepository(store, appRepository, zk.maxVersions)
        val migration = new Migration(legacyConfig, Some(store), appRepository, groupRepository,
          deploymentRepository, taskRepository, taskFailureRepository)
        StorageModuleImpl(
          appRepository,
          taskRepository,
          deploymentRepository,
          taskFailureRepository,
          groupRepository,
          migration)
      case mem: InMem =>
        val store = mem.store
        val appRepository = AppRepository.inMemRepository(store, mem.maxVersions)
        val taskRepository = TaskRepository.inMemRepository(store)
        val deploymentRepository = DeploymentRepository.inMemRepository(store)
        val taskFailureRepository = TaskFailureRepository.inMemRepository(store)
        val groupRepository = GroupRepository.inMemRepository(store, appRepository, mem.maxVersions)
        val migration = new Migration(legacyConfig, Some(store), appRepository, groupRepository,
          deploymentRepository, taskRepository, taskFailureRepository)
        StorageModuleImpl(
          appRepository,
          taskRepository,
          deploymentRepository,
          taskFailureRepository,
          groupRepository,
          migration)
    }
  }
}

private[storage] case class StorageModuleImpl(
  appRepository: ReadOnlyAppRepository,
  taskRepository: TaskRepository,
  deploymentRepository: DeploymentRepository,
  taskFailureRepository: TaskFailureRepository,
  groupRepository: GroupRepository,
  migration: Migration) extends StorageModule
