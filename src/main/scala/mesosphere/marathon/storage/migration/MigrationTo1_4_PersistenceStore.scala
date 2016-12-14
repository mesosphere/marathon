package mesosphere.marathon.storage.migration

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.event.EventSubscribers
import mesosphere.marathon.core.instance.Instance.{ AgentInfo, InstanceState }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.repository._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, MarathonTaskState, TaskFailure }
import mesosphere.marathon.storage.LegacyStorageConfig
import mesosphere.marathon.storage.repository.{ AppRepository, DeploymentRepository, EventSubscribersRepository, FrameworkIdRepository, GroupRepository, InstanceRepository, PodRepository, TaskFailureRepository, TaskRepository }
import mesosphere.marathon.stream._
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
            migrateTasks(legacyConfig, migration.taskRepo, migration.instanceRepo),
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

  @SuppressWarnings(Array("all")) // async/await
  private[this] def migrateTasks(legacyStore: LegacyStorageConfig, taskRepository: TaskRepository,
    instanceRepository: InstanceRepository): Future[(String, Int)] = {
    val oldTaskRepo = TaskRepository.legacyRepository(legacyStore.entityStore[MarathonTaskState])
    val oldInstanceRepo = InstanceRepository.legacyRepository(legacyStore.entityStore[Instance])
    async { // linter:ignore:UnnecessaryElseBranch
      // first, migrate from the legacy task repositories, then overwrite with legacy instance store -> new one
      // basically, only one of the two existed.
      val oldTaskIds = await(oldTaskRepo.ids().runWith(Sink.seq))
      val (tasksToMigrateSource, storeToDeleteFrom) = if (oldTaskIds.nonEmpty) {
        (Source(oldTaskIds).mapAsync(1)(oldTaskRepo.getRaw).collect { case Some(t) => t }, oldTaskRepo)
      } else {
        (Source.empty[MarathonTask], taskRepository)
      }

      val (key, migratedTasksCount) = await {
        tasksToMigrateSource.mapAsync(Int.MaxValue) { proto =>
          val instance = marathonTaskToInstance(proto)
          val taskId = Task.Id(proto.getId)
          instanceRepository.store(instance).andThen {
            case _ => storeToDeleteFrom.delete(taskId)
          }
        }.runFold(0) { case (acc, _) => acc + 1 }.map("tasks" -> _)
      }

      val migratedInstancesCount = await(migrateRepo(oldInstanceRepo, instanceRepository))

      key -> (migratedTasksCount + migratedInstancesCount)
    }
  }

  private[migration] def marathonTaskToInstance(proto: MarathonTask): Instance = {
    val task = TaskSerializer.fromProto(proto)
    val since = task.status.startedAt.getOrElse(task.status.stagedAt)
    val tasksMap = Map(task.taskId -> task)
    val state = InstanceState(maybeOldState = None, tasksMap, since)

    // TODO: implement proper deserialization/conversion (DCOS-10332)
    val agentInfo: AgentInfo = {
      val host = if (proto.hasOBSOLETEHost) {
        proto.getOBSOLETEHost
      } else throw new IllegalArgumentException(s"task[${proto.getId}]: host must be set")

      val agentId = if (proto.hasOBSOLETESlaveId) {
        Some(proto.getOBSOLETESlaveId.getValue)
      } else Option.empty[String]

      val attributes = proto.getOBSOLETEAttributesList.toIndexedSeq

      AgentInfo(host, agentId, attributes)
    }

    Instance(task.taskId.instanceId, agentInfo, state, tasksMap, task.runSpecVersion)
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
      groupRepository.storeRoot(root, root.transitiveApps.toIndexedSeq, Nil,
        root.transitivePodsById.values.toIndexedSeq, Nil).map(_ =>
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
