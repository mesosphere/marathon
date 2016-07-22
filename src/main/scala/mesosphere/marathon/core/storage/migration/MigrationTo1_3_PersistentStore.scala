package mesosphere.marathon.core.storage.migration

// scalastyle:off
import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.LegacyStorageConfig
import mesosphere.marathon.core.storage.repository.{ AppRepository, DeploymentRepository, FrameworkIdRepository, GroupRepository, Repository, TaskFailureRepository, TaskRepository, VersionedRepository }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, MarathonTaskState, TaskFailure }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.state.FrameworkId

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }
// scalastyle:on

/**
  * Migration from Legacy Storage in 1.2 to the new Persistence Storage in 1.3
  *
  * Does nothing unless Legacy Storage and New Storage are configured.
  */
class MigrationTo1_3_PersistentStore(migration: Migration)(implicit
  executionContext: ExecutionContext,
    mat: Materializer,
    metrics: Metrics) extends StrictLogging {
  def migrate(): Future[Done] = async {
    val legacyStore = await(migration.legacyStoreFuture)
    // This migration only goes from legacy storage -> new storage.
    (legacyStore, migration.persistenceStore, migration.legacyConfig) match {
      case (Some(_), Some(_), Some(legacyConfig)) =>
        val futures = legacyStore.map { _ =>
          val legacyConfig = {
            // if persistent store is defined, the config has to be too.
            require(migration.legacyConfig.isDefined)
            migration.legacyConfig.get
          }
          Seq(
            migrateTasks(legacyConfig, migration.taskRepo),
            migrateDeployments(legacyConfig, migration.deploymentRepository),
            migrateTaskFailures(legacyConfig, migration.taskFailureRepo),
            // note: we don't actually need to migrate apps (group does it)
            migrateGroups(legacyConfig, migration.groupRepository),
            migrateFrameworkId(legacyConfig, migration.frameworkIdRepo)
          )
        }.getOrElse(Nil)
        val summary = await(Future.sequence(futures))
        logger.info(s"Migrated ${summary.mkString} to 1.3")
        Done
      case _ =>
        logger.info("Skipping Curator Persistence Migration (no legacy store/persistent store in use)")
        Done
    }
  }

  def migrateRepo[T](oldRepo: Repository[_, T], newRepo: Repository[_, T]): Future[Int] = {
    oldRepo.all().mapAsync(Int.MaxValue) { value =>
      newRepo.store(value)
    }.runFold(0) { case (acc, _) => acc + 1 }
  }

  def migrateVersionedRepo[Id, T](
    oldRepo: VersionedRepository[Id, T],
    newRepo: VersionedRepository[Id, T]): Future[Int] = async {
    val oldVersions = oldRepo.ids().flatMapConcat { id =>
      oldRepo.versions(id).mapAsync(Int.MaxValue) { version =>
        oldRepo.getVersion(id, version)
      }.collect { case Some(value) => value }
    }.mapAsync(1) { value =>
      oldRepo.storeVersion(value)
    }.runFold(0) { case (acc, _) => acc + 1 }

    val currentVersions = oldRepo.all().mapAsync(1) { value =>
      newRepo.store(value)
    }.runFold(0) { case (acc, _) => acc + 1 }

    await(oldVersions) + await(currentVersions)
  }

  def migrateTasks(legacyStore: LegacyStorageConfig, taskRepository: TaskRepository): Future[(String, Int)] = {
    val oldRepo = TaskRepository.legacyRepository(legacyStore.entityStore[MarathonTaskState])
    migrateRepo(oldRepo, taskRepository).map("tasks" -> _)
  }

  def migrateDeployments(
    legacyStore: LegacyStorageConfig,
    deploymentRepository: DeploymentRepository): Future[(String, Int)] = {
    val oldRepo = DeploymentRepository.legacyRepository(legacyStore.entityStore[DeploymentPlan])
    migrateRepo(oldRepo, deploymentRepository).map("deployment plans" -> _)
  }

  def migrateTaskFailures(
    legacyStore: LegacyStorageConfig,
    taskFailureRepository: TaskFailureRepository): Future[(String, Int)] = {
    val oldRepo = TaskFailureRepository.legacyRepository(legacyStore.entityStore[TaskFailure])
    migrateVersionedRepo(oldRepo, taskFailureRepository).map("task failures" -> _)
  }

  def migrateGroups(
    legacyStore: LegacyStorageConfig,
    groupRepository: GroupRepository): Future[(String, Int)] = {
    val oldAppRepo = AppRepository.legacyRepository(legacyStore.entityStore[AppDefinition], legacyStore.maxVersions)
    val oldRepo = GroupRepository.legacyRepository(legacyStore.entityStore[Group], legacyStore.maxVersions, oldAppRepo)

    oldRepo.rootVersions().mapAsync(Int.MaxValue) { version =>
      oldRepo.rootVersion(version)
    }.collect {
      case Some(root) => root
    }.concat { Source.fromFuture(oldRepo.root()) }.mapAsync(1) { root =>
      // we store the roots one at a time with the current root last,
      // adding a new app version for every root (for simplicity)
      groupRepository.storeRoot(root, root.apps.values.toVector, Nil)
    }.runFold(0) { case (acc, _) => acc + 1 }.map("root versions" -> _)
  }

  def migrateFrameworkId(
    legacyStore: LegacyStorageConfig,
    frameworkIdRepository: FrameworkIdRepository): Future[(String, Int)] = {
    val oldRepo = FrameworkIdRepository.legacyRepository(legacyStore.entityStore[FrameworkId])
    async {
      await(oldRepo.get()) match {
        case Some(v) =>
          await(frameworkIdRepository.store(v))
          "framework-id" -> 1
        case None =>
          "framework-id" -> 0
      }
    }
  }
}
