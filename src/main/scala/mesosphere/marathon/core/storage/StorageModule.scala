package mesosphere.marathon.core.storage

// scalastyle:off
import java.util.UUID

import akka.actor.{ ActorRefFactory, Scheduler }
import akka.stream.Materializer
import com.typesafe.config.Config
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.storage.repository.impl.legacy.TaskEntityRepository
import mesosphere.marathon.core.storage.repository.{ AppRepository, DeploymentRepository, GroupRepository, ReadOnlyAppRepository, TaskFailureRepository, TaskRepository }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, MarathonTaskState, PathId, TaskFailure }
import mesosphere.marathon.upgrade.DeploymentPlan
import org.apache.mesos.Protos.{ TaskID, TaskState }

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

        StorageModuleImpl(appRepository, taskRepository, deploymentRepository, taskFailureRepository, groupRepository)
      case zk: CuratorZk =>
        val store = zk.store
        val appRepo = AppRepository.zkRepository(store, zk.maxVersions)
        StorageModuleImpl(
          appRepo,
          TaskRepository.zkRepository(store),
          DeploymentRepository.zkRepository(store),
          TaskFailureRepository.zkRepository(store),
          GroupRepository.zkRepository(store, appRepo, zk.maxVersions))
      case mem: InMem =>
        val store = mem.store
        val appRepo = AppRepository.inMemRepository(store, mem.maxVersions)
        StorageModuleImpl(
          appRepo,
          TaskRepository.inMemRepository(store),
          DeploymentRepository.inMemRepository(store),
          TaskFailureRepository.inMemRepository(store),
          GroupRepository.inMemRepository(store, appRepo, mem.maxVersions))
    }
  }
}

private[storage] case class StorageModuleImpl(
  appRepository: AppRepository,
  taskRepository: TaskRepository,
  deploymentRepository: DeploymentRepository,
  taskFailureRepository: TaskFailureRepository,
  groupRepository: GroupRepository) extends StorageModule
