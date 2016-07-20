package mesosphere.marathon.core.storage

// scalastyle:off
import java.util.UUID

import akka.actor.{ActorRefFactory, Scheduler}
import akka.stream.Materializer
import com.typesafe.config.Config
import mesosphere.marathon.util.toRichConfig
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.storage.migration.Migration
import mesosphere.marathon.core.storage.repository.impl.legacy.TaskEntityRepository
import mesosphere.marathon.core.storage.repository.{AppRepository, DeploymentRepository, GroupRepository, ReadOnlyAppRepository, TaskFailureRepository, TaskRepository}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{AppDefinition, Group, MarathonTaskState, PathId, TaskFailure}
import mesosphere.marathon.upgrade.DeploymentPlan
import org.apache.mesos.Protos.{TaskID, TaskState}

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
  def apply(conf: MarathonConf)(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): StorageModule = {
    val currentConfig = StorageConfig(conf)
    val legacyConfig = conf.internalStoreBackend() match {
      case TwitterZk.StoreName | MesosZk.StoreName | InMem.StoreName => None
      case CuratorZk.StoreName => Some(TwitterZk(cache = false, conf))
    }
    apply(currentConfig, legacyConfig)
  }

  def apply(config: Config)(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): StorageModule = {

    val currentConfig = StorageConfig(config)
    val legacyConfig = config.optionalConfig("migration")
      .map(StorageConfig(_)).collect { case l:LegacyStorageConfig => l }
    apply(currentConfig, legacyConfig)
  }


  def apply(config: StorageConfig, legacyConfig: Option[LegacyStorageConfig])
           (implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): StorageModule = {

    config match {
      case l: LegacyStorageConfig =>
        val appRepository = AppRepository.legacyRepository(
          l.entityStore("app:", () => AppDefinition.apply()),
          l.maxVersions
        )
        val taskRepository = TaskRepository.legacyRepository(l.entityStore(
          TaskEntityRepository.storePrefix,
          () => MarathonTaskState(MarathonTask.newBuilder().setId(UUID.randomUUID().toString).build())))
        val deploymentRepository = DeploymentRepository.legacyRepository(l.entityStore(
          "deployment:",
          () => DeploymentPlan.empty
        ))
        val taskFailureRepository = TaskFailureRepository.legacyRepository(l.entityStore(
          "taskFailure:",
          () => TaskFailure(
            PathId.empty,
            TaskID.newBuilder().setValue("").build,
            TaskState.TASK_STAGING
          )
        ), 1)
        val groupRepository = GroupRepository.legacyRepository(l.entityStore(
          "group:",
          () => Group.empty
        ), l.maxVersions, appRepository)

        val migration = new Migration(legacyConfig,
          appRepository,
          groupRepository,
          deploymentRepository,
          taskRepository,
          taskFailureRepository)

        StorageModuleImpl(appRepository,
          taskRepository,
          deploymentRepository,
          taskFailureRepository,
          groupRepository,
          migration)
      case zk: CuratorZk =>
        val store = zk.store
        val appRepo = AppRepository.zkRepository(store, zk.maxVersions)
        val groupRepo = GroupRepository.zkRepository(store, appRepo, zk.maxVersions)
        val deploymentRepo = DeploymentRepository.zkRepository(store)
        val taskRepo = TaskRepository.zkRepository(store)
        val taskFailureRepo = TaskFailureRepository.zkRepository(store)
        val migration = new Migration(legacyConfig, appRepo, groupRepo, deploymentRepo, taskRepo, taskFailureRepo)
        StorageModuleImpl(
          appRepo,
          taskRepo,
          deploymentRepo,
          taskFailureRepo,
          groupRepo,
          migration)
      case mem: InMem =>
        val store = mem.store
        val appRepo = AppRepository.inMemRepository(store, mem.maxVersions)
        val groupRepo = GroupRepository.inMemRepository(store, appRepo, mem.maxVersions)
        val deploymentRepo = DeploymentRepository.inMemRepository(store)
        val taskRepo = TaskRepository.inMemRepository(store)
        val taskFailureRepo = TaskFailureRepository.inMemRepository(store)
        val migration = new Migration(legacyConfig, appRepo, groupRepo, deploymentRepo, taskRepo, taskFailureRepo)
        StorageModuleImpl(
          appRepo,
          taskRepo,
          deploymentRepo,
          taskFailureRepo,
          groupRepo,
          migration
        )
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
