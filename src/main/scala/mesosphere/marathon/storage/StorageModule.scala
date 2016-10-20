package mesosphere.marathon.storage

import akka.actor.{ ActorRefFactory, Scheduler }
import akka.stream.Materializer
import com.typesafe.config.Config
import mesosphere.marathon.PrePostDriverCallback
import mesosphere.marathon.core.event.EventSubscribers
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.store.impl.cache.LoadTimeCachingPersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, MarathonTaskState, TaskFailure }
import mesosphere.marathon.storage.migration.Migration
import mesosphere.marathon.storage.repository._
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.util.toRichConfig
import mesosphere.util.state.FrameworkId

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext

/**
  * Provides the repositories for all persistable entities.
  */
trait StorageModule {
  val appRepository: ReadOnlyAppRepository
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
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): StorageModule = {
    val currentConfig = StorageConfig(conf)
    val legacyConfig = conf.internalStoreBackend() match {
      case TwitterZk.StoreName => Some(TwitterZk(conf))
      case MesosZk.StoreName => Some(MesosZk(conf))
      case CuratorZk.StoreName => Some(TwitterZk(conf))
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
        val appStore = l.entityStore[AppDefinition] _
        val appRepository = AppRepository.legacyRepository(appStore, l.maxVersions)
        val podStore = l.entityStore[PodDefinition] _
        val podRepository = PodRepository.legacyRepository(podStore, l.maxVersions)
        val taskStore = l.entityStore[MarathonTaskState] _
        val taskRepository = TaskRepository.legacyRepository(taskStore)
        val instanceStore = l.entityStore[Instance] _
        val instanceRepository = InstanceRepository.legacyRepository(instanceStore)
        val deployStore = l.entityStore[DeploymentPlan] _
        val deploymentRepository = DeploymentRepository.legacyRepository(deployStore)
        val taskFailureStore = l.entityStore[TaskFailure] _
        val taskFailureRepository = TaskFailureRepository.legacyRepository(taskFailureStore)
        val groupStore = l.entityStore[Group] _
        val groupRepository = GroupRepository.legacyRepository(groupStore, l.maxVersions, appRepository, podRepository)
        val frameworkIdStore = l.entityStore[FrameworkId] _
        val frameworkIdRepository = FrameworkIdRepository.legacyRepository(frameworkIdStore)
        val eventSubscribersStore = l.entityStore[EventSubscribers] _
        val eventSubscribersRepository = EventSubscribersRepository.legacyRepository(eventSubscribersStore)

        val migration = new Migration(legacyConfig, None, appRepository, groupRepository,
          deploymentRepository, taskRepository, instanceRepository, taskFailureRepository,
          frameworkIdRepository, eventSubscribersRepository)

        val leadershipInitializers = Seq(appStore, taskStore, deployStore, taskFailureStore,
          groupStore, frameworkIdStore, eventSubscribersStore).collect { case s: PrePostDriverCallback => s }

        StorageModuleImpl(appRepository, podRepository, instanceRepository, deploymentRepository,
          taskFailureRepository, groupRepository, frameworkIdRepository, eventSubscribersRepository, migration,
          leadershipInitializers)
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

        val migration = new Migration(legacyConfig, Some(store), appRepository, groupRepository,
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

        val migration = new Migration(legacyConfig, Some(store), appRepository, groupRepository,
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
