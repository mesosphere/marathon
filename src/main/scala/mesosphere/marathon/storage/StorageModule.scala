package mesosphere.marathon
package storage

import akka.actor.{ActorSystem, Scheduler}
import akka.stream.Materializer
import mesosphere.marathon.core.storage.backup.PersistentStoreBackup
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.cache.LoadTimeCachingPersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.RichCuratorFramework
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.migration.{Migration, ServiceDefinitionRepository}
import mesosphere.marathon.storage.repository._

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext

/**
  * Provides the repositories for all persistable entities.
  */
trait StorageModule {
  val persistenceStore: PersistenceStore[_, _, _]
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
  def apply(metrics: Metrics, conf: StorageConf with NetworkConf, curatorFramework: RichCuratorFramework)(
    implicit
    mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): StorageModule = {
    val currentConfig = StorageConfig(conf, curatorFramework)
    apply(metrics, currentConfig, conf.mesosBridgeName())
  }

  def apply(
    metrics: Metrics, config: StorageConfig, mesosBridgeName: String)(
    implicit
    mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): StorageModule = {

    config match {
      case zk: CuratorZk =>
        val store = zk.store(metrics)
        val appRepository = AppRepository.zkRepository(store)
        val podRepository = PodRepository.zkRepository(store)
        val groupRepository = GroupRepository.zkRepository(store, appRepository, podRepository, zk.groupVersionsCacheSize)

        val instanceRepository = InstanceRepository.zkRepository(store)
        val deploymentRepository = DeploymentRepository.zkRepository(metrics, store, groupRepository,
          appRepository, podRepository, zk.maxVersions, zk.storageCompactionScanBatchSize, zk.storageCompactionInterval)
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
          store,
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
        val store = mem.store(metrics)
        val appRepository = AppRepository.inMemRepository(store)
        val podRepository = PodRepository.inMemRepository(store)
        val instanceRepository = InstanceRepository.inMemRepository(store)
        val groupRepository = GroupRepository.inMemRepository(store, appRepository, podRepository, mem.groupVersionsCacheSize)
        val deploymentRepository = DeploymentRepository.inMemRepository(metrics, store, groupRepository,
          appRepository, podRepository, mem.maxVersions, mem.storageCompactionScanBatchSize)
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
          store,
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
    persistenceStore: PersistenceStore[_, _, _],
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
