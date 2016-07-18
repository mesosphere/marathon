package mesosphere.marathon.core.storage

import java.util.UUID

// scalastyle:off
import akka.actor.{ActorRefFactory, Scheduler}
import akka.stream.Materializer
import com.typesafe.config.Config
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.storage.repository.{AppRepository, DeploymentRepository, TaskFailureRepository, TaskRepository}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{AppDefinition, AppEntityRepository, DeploymentEntityRepository, MarathonTaskState, PathId, TaskEntityRepository, TaskFailure, TaskFailureEntityRepository}
import mesosphere.marathon.upgrade.DeploymentPlan
import org.apache.mesos.Protos.TaskID
import org.apache.mesos.Protos.TaskState

import scala.concurrent.ExecutionContext
// scalastyle:on

/**
  * Provides the repositories for all persistable entities.
  */
trait StorageModule {
  def appRepository: AppRepository
  def taskRepository: TaskRepository
  def deploymentRepository: DeploymentRepository
  def taskFailureRepository: TaskFailureRepository
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
        val deploymentRepository = new DeploymentEntityRepository(l.entityStore(
          "deployment:",
          () => DeploymentPlan.empty
        ), metrics)
        val taskFailureRepository = new TaskFailureEntityRepository(l.entityStore(
          "taskFailure:",
          () => TaskFailure(
            PathId.empty,
            TaskID.newBuilder().setValue("").build,
            TaskState.TASK_STAGING
          )
        ), Some(1), metrics)

        StorageModuleImpl(appRepository, taskRepository, deploymentRepository, taskFailureRepository)
      case zk: NewZk =>
        val store = zk.store
        StorageModuleImpl(AppRepository.zkRepository(store, zk.maxVersions),
          TaskRepository.zkRepository(store),
          DeploymentRepository.zkRepository(store),
          TaskFailureRepository.zkRepository(store))
      case mem: NewInMem =>
        val store = mem.store
        StorageModuleImpl(AppRepository.inMemRepository(store, mem.maxVersions),
          TaskRepository.inMemRepository(store),
          DeploymentRepository.inMemRepository(store),
          TaskFailureRepository.inMemRepository(store))
    }
  }
}

private[storage] case class StorageModuleImpl(
  appRepository: AppRepository,
  taskRepository: TaskRepository,
  deploymentRepository: DeploymentRepository,
  taskFailureRepository: TaskFailureRepository) extends StorageModule
