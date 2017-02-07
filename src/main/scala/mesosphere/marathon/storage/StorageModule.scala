package mesosphere.marathon
package storage

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.Materializer
import mesosphere.marathon.core.storage.store.impl.cache.LoadTimeCachingPersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.migration.Migration
import mesosphere.marathon.storage.repository._

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext

/**
  * Provides the repositories for all persistable entities.
  */
trait StorageModule {
  // Should _only_ be used by the GroupManager, always use the RootGroup as the one source of truth
  val appRepository: ReadOnlyAppRepository
  // Should _only_ be used by the GroupManager, always use the RootGroup as the one source of truth
  val podRepository: ReadOnlyPodRepository
  val instanceRepository: InstanceRepository
  val deploymentRepository: DeploymentRepository
  val taskFailureRepository: TaskFailureRepository
  val groupRepository: GroupRepository
  val frameworkIdRepository: FrameworkIdRepository
  val eventSubscribersRepository: EventSubscribersRepository
  val migration: Migration
  val leadershipInitializers: Seq[PrePostDriverCallback]
}

object StorageModule {
  def apply(conf: StorageConf)(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): StorageModule = {
    val currentConfig = StorageConfig(conf)
    apply(currentConfig)
  }

  def apply(
    config: StorageConfig)(implicit
    metrics: Metrics,
    mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): StorageModule = {

    config match {
      case zk: CuratorZk =>
        val store = zk.store
        val appRepository = AppRepository.zkRepository(store)
        val podRepository = PodRepository.zkRepository(store)
        val groupRepository = GroupRepository.zkRepository(store, appRepository, podRepository)

        val taskRepository = TaskRepository.zkRepository(store)
        val instanceRepository = InstanceRepository.zkRepository(store)
        val deploymentRepository = DeploymentRepository.zkRepository(store, groupRepository,
          appRepository, podRepository, zk.maxVersions)
        val taskFailureRepository = TaskFailureRepository.zkRepository(store)
        val frameworkIdRepository = FrameworkIdRepository.zkRepository(store)
        val eventSubscribersRepository = EventSubscribersRepository.zkRepository(store)

        val leadershipInitializers = store match {
          case s: LoadTimeCachingPersistenceStore[_, _, _] =>
            Seq(s)
          case _ =>
            Nil
        }

        val migration = new Migration(zk.availableFeatures, store, appRepository, groupRepository,
          deploymentRepository, taskRepository, instanceRepository, taskFailureRepository,
          frameworkIdRepository, eventSubscribersRepository)
        StorageModuleImpl(
          appRepository,
          podRepository,
          instanceRepository,
          deploymentRepository,
          taskFailureRepository,
          groupRepository,
          frameworkIdRepository,
          eventSubscribersRepository,
          migration,
          leadershipInitializers)
      case mem: InMem =>
        val store = mem.store
        val appRepository = AppRepository.inMemRepository(store)
        val podRepository = PodRepository.inMemRepository(store)
        val taskRepository = TaskRepository.inMemRepository(store)
        val instanceRepository = InstanceRepository.inMemRepository(store)
        val groupRepository = GroupRepository.inMemRepository(store, appRepository, podRepository)
        val deploymentRepository = DeploymentRepository.inMemRepository(store, groupRepository,
          appRepository, podRepository, mem.maxVersions)
        val taskFailureRepository = TaskFailureRepository.inMemRepository(store)
        val frameworkIdRepository = FrameworkIdRepository.inMemRepository(store)
        val eventSubscribersRepository = EventSubscribersRepository.inMemRepository(store)

        val leadershipInitializers = store match {
          case s: LoadTimeCachingPersistenceStore[_, _, _] =>
            Seq(s)
          case _ =>
            Nil
        }

        val migration = new Migration(mem.availableFeatures, store, appRepository, groupRepository,
          deploymentRepository, taskRepository, instanceRepository, taskFailureRepository,
          frameworkIdRepository, eventSubscribersRepository)
        StorageModuleImpl(
          appRepository,
          podRepository,
          instanceRepository,
          deploymentRepository,
          taskFailureRepository,
          groupRepository,
          frameworkIdRepository,
          eventSubscribersRepository,
          migration,
          leadershipInitializers)
    }
  }
}

private[storage] case class StorageModuleImpl(
  appRepository: ReadOnlyAppRepository,
  podRepository: ReadOnlyPodRepository,
  instanceRepository: InstanceRepository,
  deploymentRepository: DeploymentRepository,
  taskFailureRepository: TaskFailureRepository,
  groupRepository: GroupRepository,
  frameworkIdRepository: FrameworkIdRepository,
  eventSubscribersRepository: EventSubscribersRepository,
  migration: Migration,
  leadershipInitializers: Seq[PrePostDriverCallback]) extends StorageModule
