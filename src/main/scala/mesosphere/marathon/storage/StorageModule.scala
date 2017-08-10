package mesosphere.marathon
package storage

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.Materializer
import mesosphere.marathon.core.base.LifecycleState
import mesosphere.marathon.core.storage.backup.PersistentStoreBackup
import mesosphere.marathon.core.storage.store.impl.cache.LoadTimeCachingPersistenceStore
import mesosphere.marathon.storage.migration.{ Migration, ServiceDefinitionRepository }
import mesosphere.marathon.storage.repository._

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext

/**
  * Provides the repositories for all persistable entities.
  */
trait StorageModule {
  val instanceRepository: InstanceRepository
  val deploymentRepository: DeploymentRepository
  val taskFailureRepository: TaskFailureRepository
  val groupRepository: GroupRepository
  val frameworkIdRepository: FrameworkIdRepository
  val runtimeConfigurationRepository: RuntimeConfigurationRepository
  val migration: Migration
  val leadershipInitializers: Seq[PrePostDriverCallback]
  val persistentStoreBackup: PersistentStoreBackup
}

object StorageModule {
  def apply(conf: StorageConf with NetworkConf, lifecycleState: LifecycleState)(implicit mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): StorageModule = {
    val currentConfig = StorageConfig(conf, lifecycleState)
    apply(currentConfig, conf.mesosBridgeName())
  }

  def apply(
    config: StorageConfig, mesosBridgeName: String)(implicit mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): StorageModule = {

    config match {
      case zk: CuratorZk =>
        val store = zk.store
        val appRepository = AppRepository.zkRepository(store)
        val podRepository = PodRepository.zkRepository(store)
        val groupRepository = GroupRepository.zkRepository(store, appRepository, podRepository)

        val instanceRepository = InstanceRepository.zkRepository(store)
        val deploymentRepository = DeploymentRepository.zkRepository(store, groupRepository,
          appRepository, podRepository, zk.maxVersions)
        val taskFailureRepository = TaskFailureRepository.zkRepository(store)
        val frameworkIdRepository = FrameworkIdRepository.zkRepository(store)
        val runtimeConfigurationRepository = RuntimeConfigurationRepository.zkRepository(store)

        val leadershipInitializers = store match {
          case s: LoadTimeCachingPersistenceStore[_, _, _] =>
            Seq(s)
          case _ =>
            Nil
        }

        val backup = PersistentStoreBackup(store)
        val migration = new Migration(zk.availableFeatures, zk.defaultNetworkName, mesosBridgeName, store, appRepository, podRepository, groupRepository,
          deploymentRepository, instanceRepository,
          taskFailureRepository, frameworkIdRepository, ServiceDefinitionRepository.zkRepository(store), runtimeConfigurationRepository, backup, config)

        StorageModuleImpl(
          instanceRepository,
          deploymentRepository,
          taskFailureRepository,
          groupRepository,
          frameworkIdRepository,
          runtimeConfigurationRepository,
          migration,
          leadershipInitializers,
          backup
        )
      case mem: InMem =>
        val store = mem.store
        val appRepository = AppRepository.inMemRepository(store)
        val podRepository = PodRepository.inMemRepository(store)
        val instanceRepository = InstanceRepository.inMemRepository(store)
        val groupRepository = GroupRepository.inMemRepository(store, appRepository, podRepository)
        val deploymentRepository = DeploymentRepository.inMemRepository(store, groupRepository,
          appRepository, podRepository, mem.maxVersions)
        val taskFailureRepository = TaskFailureRepository.inMemRepository(store)
        val frameworkIdRepository = FrameworkIdRepository.inMemRepository(store)
        val runtimeConfigurationRepository = RuntimeConfigurationRepository.inMemRepository(store)

        val leadershipInitializers = store match {
          case s: LoadTimeCachingPersistenceStore[_, _, _] =>
            Seq(s)
          case _ =>
            Nil
        }

        val backup = PersistentStoreBackup(store)
        val migration = new Migration(mem.availableFeatures, mem.defaultNetworkName, mesosBridgeName, store, appRepository, podRepository, groupRepository,
          deploymentRepository, instanceRepository,
          taskFailureRepository, frameworkIdRepository, ServiceDefinitionRepository.inMemRepository(store), runtimeConfigurationRepository, backup, config)

        StorageModuleImpl(
          instanceRepository,
          deploymentRepository,
          taskFailureRepository,
          groupRepository,
          frameworkIdRepository,
          runtimeConfigurationRepository,
          migration,
          leadershipInitializers,
          backup
        )
    }
  }
}

private[storage] case class StorageModuleImpl(
  instanceRepository: InstanceRepository,
  deploymentRepository: DeploymentRepository,
  taskFailureRepository: TaskFailureRepository,
  groupRepository: GroupRepository,
  frameworkIdRepository: FrameworkIdRepository,
  runtimeConfigurationRepository: RuntimeConfigurationRepository,
  migration: Migration,
  leadershipInitializers: Seq[PrePostDriverCallback],
  persistentStoreBackup: PersistentStoreBackup
) extends StorageModule
