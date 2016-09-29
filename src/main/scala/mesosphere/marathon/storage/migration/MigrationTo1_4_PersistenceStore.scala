package mesosphere.marathon.storage.migration

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.EventSubscribers
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.repository._
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, MarathonTaskState, TaskFailure }
import mesosphere.marathon.storage.LegacyStorageConfig
import mesosphere.marathon.storage.repository.{ AppRepository, DeploymentRepository, EventSubscribersRepository, FrameworkIdRepository, GroupRepository, PodRepository, TaskFailureRepository, TaskRepository }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.util.toRichFuture
import mesosphere.util.state.FrameworkId

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }

/**
  * Migration from Legacy Storage in 1.2 to the new Persistence Storage in 1.4
  *
  * Does nothing unless Legacy Storage and New Storage are configured.
  */
@SuppressWarnings(Array("ClassNames"))
class MigrationTo1_4_PersistenceStore(migration: Migration)(implicit
  executionContext: ExecutionContext,
    mat: Materializer,
    metrics: Metrics) extends StrictLogging {

  @SuppressWarnings(Array("all")) // async/await
  def migrate(): Future[Done] = async { // linter:ignore UseIfExpression+UnnecessaryElseBranch+AssigningOptionToNull
    val legacyStore = await(migration.legacyStoreFuture)
    (legacyStore, migration.persistenceStore, migration.legacyConfig) match {
      case (Some(_), Some(_), Some(legacyConfig)) =>
        val futures = legacyStore.map { _ =>
          Seq(
            migrateTasks(legacyConfig, migration.taskRepo),
            migrateDeployments(legacyConfig, migration.deploymentRepository),
            migrateTaskFailures(legacyConfig, migration.taskFailureRepo),
            // note: we don't actually need to migrate apps (group does it)
            migrateGroups(legacyConfig, migration.groupRepository),
            migrateFrameworkId(legacyConfig, migration.frameworkIdRepo),
            migrateEventSubscribers(legacyConfig, migration.eventSubscribersRepo)
          )
        }.getOrElse(Nil)
        val summary = await(Future.sequence(futures))
        logger.info(s"Migrated ${summary.mkString} to new format")
        Done
      case _ =>
        logger.info("Skipping Curator Persistence Migration (no legacy store/persistent store in use)")
        Done
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private[this] def migrateRepo[Id, T](oldRepo: Repository[Id, T], newRepo: Repository[Id, T]): Future[Int] =
    async { // linter:ignore UnnecessaryElseBranch
      val migrated = await {
        oldRepo.all().mapAsync(Int.MaxValue) { value =>
          newRepo.store(value)
        }.runFold(0) { case (acc, _) => acc + 1 }
      }
      await {
        oldRepo.ids().mapAsync(Int.MaxValue) { id =>
          oldRepo.delete(id)
        }.runWith(Sink.ignore).asTry
      }
      migrated
    }

  @SuppressWarnings(Array("all")) // async/await
  private[this] def migrateVersionedRepo[Id, T](
    oldRepo: VersionedRepository[Id, T],
    newRepo: VersionedRepository[Id, T]): Future[Int] = async { // linter:ignore UnnecessaryElseBranch
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

    val result = await(oldVersions) + await(currentVersions)
    await(oldRepo.ids().mapAsync(Int.MaxValue)(oldRepo.delete).runWith(Sink.ignore).asTry)
    result
  }

  def migrateTasks(legacyStore: LegacyStorageConfig, taskRepository: TaskRepository): Future[(String, Int)] = {
    val oldRepo = TaskRepository.legacyRepository(legacyStore.entityStore[MarathonTaskState])
    migrateRepo(oldRepo, taskRepository).map("tasks" -> _)
  }

  private[this] def migrateDeployments(
    legacyStore: LegacyStorageConfig,
    deploymentRepository: DeploymentRepository): Future[(String, Int)] = {
    val oldRepo = DeploymentRepository.legacyRepository(legacyStore.entityStore[DeploymentPlan])
    migrateRepo(oldRepo, deploymentRepository).map("deployment plans" -> _)
  }

  private[this] def migrateTaskFailures(
    legacyStore: LegacyStorageConfig,
    taskFailureRepository: TaskFailureRepository): Future[(String, Int)] = {
    val oldRepo = TaskFailureRepository.legacyRepository(legacyStore.entityStore[TaskFailure])
    migrateVersionedRepo(oldRepo, taskFailureRepository).map("task failures" -> _)
  }

  @SuppressWarnings(Array("all")) // async/await
  private[this] def migrateGroups(
    legacyStore: LegacyStorageConfig,
    groupRepository: GroupRepository): Future[(String, Int)] = async { // linter:ignore UnnecessaryElseBranch
    val oldAppRepo = AppRepository.legacyRepository(legacyStore.entityStore[AppDefinition], legacyStore.maxVersions)
    val oldPodRepo = PodRepository.legacyRepository(legacyStore.entityStore[PodDefinition], legacyStore.maxVersions)
    val oldRepo = GroupRepository.legacyRepository(
      legacyStore.entityStore[Group],
      legacyStore.maxVersions, oldAppRepo, oldPodRepo)

    val resultFuture = oldRepo.rootVersions().mapAsync(Int.MaxValue) { version =>
      oldRepo.rootVersion(version)
    }.collect {
      case Some(root) => root
    }.concat { Source.fromFuture(oldRepo.root()) }.mapAsync(1) { root =>
      // we store the roots one at a time with the current root last,
      // adding a new app version for every root (for simplicity)
      groupRepository.storeRoot(root, root.transitiveApps.toVector, Nil,
        root.transitivePodsById.values.toVector, Nil).map(_ =>
        root.transitiveApps.size + root.transitivePodsById.size
      )
    }.runFold(0) { case (acc, apps) => acc + apps + 1 }.map("root + app versions" -> _)
    val result = await(resultFuture)
    val deleteOldAppsFuture = oldAppRepo.ids().mapAsync(Int.MaxValue)(oldAppRepo.delete).runWith(Sink.ignore).asTry
    val deleteOldPodsFuture = oldPodRepo.ids().mapAsync(Int.MaxValue)(oldPodRepo.delete).runWith(Sink.ignore).asTry
    val deleteOldGroupsFuture = oldRepo.ids().mapAsync(Int.MaxValue)(oldRepo.delete).runWith(Sink.ignore).asTry
    await(deleteOldAppsFuture)
    await(deleteOldPodsFuture)
    await(deleteOldGroupsFuture)
    result
  }

  @SuppressWarnings(Array("all")) // async/await
  private[this] def migrateFrameworkId(
    legacyStore: LegacyStorageConfig,
    frameworkIdRepository: FrameworkIdRepository): Future[(String, Int)] = {
    val oldRepo = FrameworkIdRepository.legacyRepository(legacyStore.entityStore[FrameworkId])
    async { // linter:ignore UnnecessaryElseBranch
      await(oldRepo.get()) match {
        case Some(v) =>
          await(frameworkIdRepository.store(v))
          await(oldRepo.delete().asTry)
          "framework-id" -> 1
        case None =>
          "framework-id" -> 0
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private[this] def migrateEventSubscribers(
    legacyStorageConfig: LegacyStorageConfig,
    eventSubscribersRepository: EventSubscribersRepository): Future[(String, Int)] = {
    val oldRepo = EventSubscribersRepository.legacyRepository(legacyStorageConfig.entityStore[EventSubscribers])
    async { // linter:ignore UnnecessaryElseBranch
      await(oldRepo.get()) match {
        case Some(v) =>
          await(eventSubscribersRepository.store(v))
          await(oldRepo.delete().asTry)
          "event-subscribers" -> 1
        case None =>
          "event-subscribers" -> 0
      }
    }
  }
}
