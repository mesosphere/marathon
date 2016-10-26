package mesosphere.marathon.state

import java.io.{ ByteArrayInputStream, ObjectInputStream }
import java.util.regex.Pattern
import javax.inject.Inject

import akka.Done
import mesosphere.marathon.Protos.{ Constraint, MarathonTask, StorageVersion }
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.core.task.tracker.impl.MarathonTaskStatusSerializer
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.StorageVersions._
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ BuildInfo, MarathonConf, MigrationFailedException }
import mesosphere.util.Logging
import mesosphere.util.state.{ PersistentStore, PersistentStoreManagement }
import org.slf4j.LoggerFactory

import scala.async.Async.{ async, await }
import scala.collection.SortedSet
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal
import scala.util.Try

class Migration @Inject() (
    store: PersistentStore,
    appRepo: AppRepository,
    groupRepo: GroupRepository,
    taskRepo: TaskRepository,
    deploymentRepo: DeploymentRepository,
    config: MarathonConf,
    metrics: Metrics) extends Logging {

  //scalastyle:off magic.number

  type MigrationAction = (StorageVersion, () => Future[Any])

  private[state] val minSupportedStorageVersion = StorageVersions(0, 3, 0)

  /**
    * All the migrations, that have to be applied.
    * They get applied after the master has been elected.
    */
  def migrations: List[MigrationAction] = List(
    StorageVersions(0, 7, 0) -> { () =>
      Future.failed(new IllegalStateException("migration from 0.7.x not supported anymore"))
    },
    StorageVersions(0, 11, 0) -> { () =>
      new MigrationTo0_11(groupRepo, appRepo).migrateApps().recover {
        case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 0.11", e)
      }
    },
    StorageVersions(0, 13, 0) -> { () =>
      new MigrationTo0_13(taskRepo, store).migrate().recover {
        case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 0.13", e)
      }
    },
    StorageVersions(0, 16, 0) -> { () =>
      new MigrationTo0_16(groupRepo, appRepo).migrate().recover {
        case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 0.16", e)
      }
    },
    StorageVersions(1, 2, 0) -> { () =>
      new MigrationTo1_2(deploymentRepo, taskRepo).migrate().recover {
        case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 1.2", e)
      }
    },
    StorageVersions(1, 3, 5) -> { () =>
      new MigrationTo_1_3_5(appRepo, groupRepo, deploymentRepo).migrate().recover {
        case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 1.3.5", e)
      }
    }
  )

  def applyMigrationSteps(from: StorageVersion): Future[List[StorageVersion]] = {
    if (from < minSupportedStorageVersion && from.nonEmpty) {
      val msg = s"Migration from versions < $minSupportedStorageVersion is not supported. Your version: $from"
      throw new MigrationFailedException(msg)
    }
    migrations.filter(_._1 > from).sortBy(_._1).foldLeft(Future.successful(List.empty[StorageVersion])) {
      case (resultsFuture, (migrateVersion, change)) => resultsFuture.flatMap { res =>
        log.info(
          s"Migration for storage: ${from.str} to current: ${current.str}: " +
            s"apply change for version: ${migrateVersion.str} "
        )
        change.apply().map(_ => res :+ migrateVersion)
      }
    }
  }

  def initializeStore(): Future[Unit] = store match {
    case manager: PersistentStoreManagement => manager.initialize()
    case _: PersistentStore => Future.successful(())
  }

  def migrate(): StorageVersion = {
    val versionFuture = for {
      _ <- initializeStore()
      changes <- currentStorageVersion.flatMap(applyMigrationSteps)
      storedVersion <- storeCurrentVersion
    } yield storedVersion

    val result = versionFuture.map { version =>
      log.info(s"Migration successfully applied for version ${version.str}")
      version
    }.recover {
      case ex: MigrationFailedException => throw ex
      case NonFatal(ex) => throw new MigrationFailedException("MigrationFailed", ex)
    }

    Await.result(result, Duration.Inf)
  }

  private val storageVersionName = "internal:storage:version"

  def currentStorageVersion: Future[StorageVersion] = {
    store.load(storageVersionName).map {
      case Some(variable) => StorageVersion.parseFrom(variable.bytes.toArray)
      case None => StorageVersions.current
    }
  }

  def storeCurrentVersion: Future[StorageVersion] = {
    val bytes = StorageVersions.current.toByteArray
    store.load(storageVersionName).flatMap {
      case Some(entity) => store.update(entity.withNewContent(bytes))
      case None => store.create(storageVersionName, bytes)
    }.map{ _ => StorageVersions.current }
  }
}

/**
  * Implements the following migration logic:
  * * Add version info to the AppDefinition by looking at all saved versions.
  * * Make the groupRepository the ultimate source of truth for the latest app version.
  */
class MigrationTo0_11(groupRepository: GroupRepository, appRepository: AppRepository) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  def migrateApps(): Future[Unit] = {
    log.info("Start 0.11 migration")
    val rootGroupFuture = groupRepository.rootGroup().map(_.getOrElse(Group.empty))
    val appIdsFuture = appRepository.allPathIds()

    for {
      rootGroup <- rootGroupFuture
      appIdsFromAppRepo <- appIdsFuture
      appIds = appIdsFromAppRepo.toSet ++ rootGroup.transitiveApps.map(_.id)
      _ = log.info(s"Discovered ${appIds.size} app IDs")
      appsWithVersions <- processApps(appIds, rootGroup)
      _ <- storeUpdatedAppsInRootGroup(rootGroup, appsWithVersions)
    } yield log.info("Finished 0.11 migration")
  }

  private[this] def storeUpdatedAppsInRootGroup(
    rootGroup: Group,
    updatedApps: Iterable[AppDefinition]): Future[Unit] = {
    val updatedGroup = updatedApps.foldLeft(rootGroup){ (updatedGroup, updatedApp) =>
      updatedGroup.updateApp(updatedApp.id, _ => updatedApp, updatedApp.version)
    }
    groupRepository.store(groupRepository.zkRootName, updatedGroup).map(_ => ())
  }

  private[this] def processApps(appIds: Iterable[PathId], rootGroup: Group): Future[Vector[AppDefinition]] = {
    appIds.foldLeft(Future.successful[Vector[AppDefinition]](Vector.empty)) { (otherStores, appId) =>
      otherStores.flatMap { storedApps =>
        val maybeAppInGroup = rootGroup.app(appId)
        maybeAppInGroup match {
          case Some(appInGroup) =>
            addVersionInfo(appId, appInGroup).map(storedApps ++ _)
          case None =>
            log.warn(s"App [$appId] will be expunged because it is not contained in the group data")
            appRepository.expunge(appId).map(_ => storedApps)
        }
      }
    }
  }

  private[this] def addVersionInfo(id: PathId, appInGroup: AppDefinition): Future[Option[AppDefinition]] = {
    def addVersionInfoToVersioned(
      maybeLastApp: Option[AppDefinition],
      nextVersion: Timestamp,
      maybeNextApp: Option[AppDefinition]): Option[AppDefinition] = {
      maybeNextApp.map { nextApp =>
        maybeLastApp match {
          case Some(lastApp) if !lastApp.isUpgrade(nextApp) =>
            log.info(s"Adding versionInfo to ${nextApp.id} (${nextApp.version}): scaling or restart")
            nextApp.copy(versionInfo = lastApp.versionInfo.withScaleOrRestartChange(nextApp.version))
          case _ =>
            log.info(s"Adding versionInfo to ${nextApp.id} (${nextApp.version}): new config")
            nextApp.copy(versionInfo = AppDefinition.VersionInfo.forNewConfig(nextApp.version))
        }
      }
    }

    def loadApp(id: PathId, version: Timestamp): Future[Option[AppDefinition]] = {
      if (appInGroup.version == version) {
        Future.successful(Some(appInGroup))
      } else {
        appRepository.app(id, version)
      }
    }

    val sortedVersions = appRepository.listVersions(id).map(_.to[SortedSet])
    sortedVersions.flatMap { sortedVersionsWithoutGroup =>
      val sortedVersions = sortedVersionsWithoutGroup ++ Seq(appInGroup.version)
      log.info(s"Add versionInfo to app [$id] for ${sortedVersions.size} versions")

      sortedVersions.foldLeft(Future.successful[Option[AppDefinition]](None)) { (maybeLastAppFuture, nextVersion) =>
        for {
          maybeLastApp <- maybeLastAppFuture
          maybeNextApp <- loadApp(id, nextVersion)
          withVersionInfo = addVersionInfoToVersioned(maybeLastApp, nextVersion, maybeNextApp)
          storedResult <- withVersionInfo
            .map((newApp: AppDefinition) => appRepository.store(newApp).map(Some(_)))
            .getOrElse(maybeLastAppFuture)
        } yield storedResult
      }
    }

  }
}

class MigrationTo0_13(taskRepository: TaskRepository, store: PersistentStore) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  val entityStore = taskRepository.store

  // the bytes stored via TaskTracker are incompatible to EntityRepo, so we have to parse them 'manually'
  def fetchLegacyTask(taskKey: String): Future[Option[MarathonTask]] = {
    def deserialize(taskKey: String, source: ObjectInputStream): Option[MarathonTask] = {
      if (source.available > 0) {
        try {
          val size = source.readInt
          val bytes = new Array[Byte](size)
          source.readFully(bytes)
          Some(MarathonTask.parseFrom(bytes))
        } catch {
          case e: com.google.protobuf.InvalidProtocolBufferException =>
            None
        }
      } else {
        None
      }
    }

    store.load("task:" + taskKey).map(_.flatMap { entity =>
      val source = new ObjectInputStream(new ByteArrayInputStream(entity.bytes.toArray))
      deserialize(taskKey, source)
    })
  }

  def migrateTasks(): Future[Unit] = {
    log.info("Start 0.13 migration")

    entityStore.names().flatMap { keys =>
      log.info("Found {} tasks in store", keys.size)
      // old format is appId:appId.taskId
      val oldFormatRegex = """^.*:.*\..*$""".r
      val namesInOldFormat = keys.filter(key => oldFormatRegex.pattern.matcher(key).matches)
      log.info("{} tasks in old format need to be migrated.", namesInOldFormat.size)

      namesInOldFormat.foldLeft(Future.successful(())) { (f, nextKey) =>
        f.flatMap(_ => migrateKey(nextKey))
      }
    }.map { _ =>
      log.info("Completed 0.13 migration")
    }
  }

  // including 0.12, task keys are in format task:appId:taskId – the appId is
  // already contained the task, for example as in
  // task:my-app:my-app.13cb0cbe-b959-11e5-bb6d-5e099c92de61
  // where my-app.13cb0cbe-b959-11e5-bb6d-5e099c92de61 is the taskId containing
  // the appId as prefix. When using the generic EntityRepo, a colon
  // in the key after the prefix implicitly denotes a versioned entry, so this
  // had to be changed, even though tasks are not stored with versions. The new
  // format looks like this:
  // task:my-app.13cb0cbe-b959-11e5-bb6d-5e099c92de61
  private[state] def migrateKey(legacyKey: String): Future[Unit] = {
    fetchLegacyTask(legacyKey).flatMap {
      case Some(task) => taskRepository.store(task).flatMap { _ =>
        entityStore.expunge(legacyKey).map(_ => ())
      }
      case _ => Future.failed[Unit](new RuntimeException(s"Unable to load entity with key = $legacyKey"))
    }
  }

  def renameFrameworkId(): Future[Unit] = {
    val oldName = "frameworkId"
    val newName = "framework:id"
    def moveKey(bytes: IndexedSeq[Byte]): Future[Unit] = {
      for {
        _ <- store.create(newName, bytes)
        _ <- store.delete(oldName)
      } yield ()
    }

    store.load(newName).flatMap {
      case Some(_) =>
        log.info("framework:id already exists, no need to migrate")
        Future.successful(())
      case None =>
        store.load(oldName).flatMap {
          case None =>
            log.info("no frameworkId stored, no need to migrate")
            Future.successful(())
          case Some(entity) =>
            log.info("migrating frameworkId -> framework:id")
            moveKey(entity.bytes)
        }
    }
  }

  def migrate(): Future[Unit] = for {
    _ <- migrateTasks()
    _ <- renameFrameworkId()
  } yield ()
}

/**
  * Implements the following migration logic:
  * * Load all apps, the logic in AppDefinition.mergeFromProto will create portDefinitions from the deprecated ports
  * * Save all apps, the logic in [[AppDefinition.toProto]] will save the new portDefinitions and skip the deprecated
  *   ports
  */
class MigrationTo0_16(groupRepository: GroupRepository, appRepository: AppRepository) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  def migrate(): Future[Unit] = {
    log.info("Start 0.16 migration")
    val rootGroupFuture = groupRepository.rootGroup().map(_.getOrElse(Group.empty))

    for {
      rootGroup <- rootGroupFuture
      apps = rootGroup.transitiveApps
      _ = log.info(s"Discovered ${apps.size} apps")
      _ <- migrateRootGroup(rootGroup)
      _ <- migrateApps(rootGroup)
    } yield log.info("Finished 0.16 migration")
  }

  private[this] def migrateRootGroup(rootGroup: Group): Future[Unit] = {
    updateAllGroupVersions()
  }

  private[this] def migrateApps(rootGroup: Group): Future[Unit] = {
    val apps = rootGroup.transitiveApps

    apps.foldLeft(Future.successful(())) { (future, app) =>
      future.flatMap { _ => updateAllAppVersions(app.id) }
    }
  }

  private[this] def updateAllGroupVersions(): Future[Unit] = {
    val id = groupRepository.zkRootName
    groupRepository.listVersions(id).map(d => d.toSeq.sorted).flatMap { sortedVersions =>
      sortedVersions.foldLeft(Future.successful(())) { (future, version) =>
        future.flatMap { _ =>
          groupRepository.group(id, version).flatMap {
            case Some(group) => groupRepository.store(id, group).map(_ => ())
            case None => Future.failed(new MigrationFailedException(s"Group $id:$version not found"))
          }
        }
      }
    }
  }

  private[this] def updateAllAppVersions(appId: PathId): Future[Unit] = {
    appRepository.listVersions(appId).map(d => d.toSeq.sorted).flatMap { sortedVersions =>
      sortedVersions.foldLeft(Future.successful(())) { (future, version) =>
        future.flatMap { _ =>
          appRepository.app(appId, version).flatMap {
            case Some(app) => appRepository.store(app).map(_ => ())
            case None => Future.failed(new MigrationFailedException(s"App $appId:$version not found"))
          }
        }
      }
    }
  }
}

/**
  * Implements the following migration logic:
  * * Removes all deployment version nodes from ZK
  * * Adds calculated MarathonTaskStatus to stored tasks
  */
class MigrationTo1_2(deploymentRepository: DeploymentRepository, taskRepository: TaskRepository) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  def migrate(): Future[Unit] = async {
    log.info("Start 1.2 migration")

    val nodes: Seq[String] = await(deploymentRepository.store.names())
    val deploymentVersionNodes = nodes.filter(deploymentRepository.isVersionKey)

    val it = deploymentVersionNodes.iterator
    while (it.hasNext) {
      await(deploymentRepository.store.expunge(it.next))
    }

    val store = taskRepository.store

    def loadAndMigrateTasks(id: String): Future[Option[MarathonTaskState]] = {
      store.fetch(id).flatMap {
        case Some(entity) =>
          val proto = entity.toProto
          if (!proto.hasMarathonTaskStatus) {
            val updatedEntity = proto.toBuilder
              .setMarathonTaskStatus(
                if (proto.hasStatus) MarathonTaskStatusSerializer.toProto(MarathonTaskStatus(proto.getStatus))
                else MarathonTask.MarathonTaskStatus.Unknown
              )
              .build()
            store.store(id, MarathonTaskState(updatedEntity)).map(Some(_))
          } else {
            Future.successful(Some(entity))
          }
        case None =>
          log.warn("Inconsistency in the task store detected, " +
            s"task with id $id not found, but delivered in allIds().")
          Future.successful(None)
      }
    }

    val taskIds = await(store.names())
    log.info(s"Discovered ${taskIds.size} tasks for status migration")
    val taskIterator = taskIds.iterator
    while (taskIterator.hasNext) {
      await(loadAndMigrateTasks(taskIterator.next()))
    }

    log.info("Finished 1.2 migration")
  }
}

class MigrationTo_1_3_5(appRepository: AppRepository, groupRepository: GroupRepository,
    deploymentRepository: DeploymentRepository) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  private def isBrokenConstraint(constraint: Constraint): Boolean = {
    (constraint.getOperator == Constraint.Operator.LIKE ||
      constraint.getOperator == Constraint.Operator.UNLIKE) &&
      (constraint.hasValue && Try(Pattern.compile(constraint.getValue)).isFailure)
  }

  private def fixConstraints(app: AppDefinition): AppDefinition = {
    val newConstraints: Set[Constraint] = app.constraints.flatMap { constraint =>
      constraint.getOperator match {
        case Constraint.Operator.LIKE | Constraint.Operator.UNLIKE if isBrokenConstraint(constraint) =>
          // we know we have a value and the pattern doesn't compile already.
          if (constraint.getValue == "*") {
            Some(constraint.toBuilder.setValue(".*").build())
          } else {
            log.warn(s"Removing invalid constraint $constraint from ${app.id}")
            None
          }
        case _ => Some(constraint)
      }
    }(collection.breakOut)
    app.copy(constraints = newConstraints)
  }

  private def migrateApps(): Future[Done] = async {
    val apps = await(appRepository.apps())
    val brokenApps = apps.collect {
      case app: AppDefinition if app.constraints.exists(isBrokenConstraint) => app
    }
    log.info(s"Fixing apps with invalid constraints: ${brokenApps.map(_.id).mkString(", ")}")
    val futures = brokenApps.map(app => appRepository.store(app))
    await(Future.sequence(futures))
    Done
  }

  private def fixRoot(group: Group): Group = {
    group.updateGroup { g =>
      val apps = g.apps.map {
        case (appId, app) =>
          app match {
            case _ if app.constraints.exists(isBrokenConstraint) =>
              appId -> fixConstraints(app)
            case _ => appId -> app
          }
      }
      Some(g.copy(apps = apps))
    }.get
  }

  private def migrateRoot(): Future[Done] = async {
    await(groupRepository.rootGroup()) match {
      case Some(root) if root.transitiveApps.exists(app => app.constraints.exists(isBrokenConstraint)) =>
        log.info("Updating apps in root group as they have invalid constraints")
        await(groupRepository.store(GroupRepository.zkRootName, fixRoot(root)))
        Done
      case _ =>
        Done
    }
  }

  private def migratePlans(): Future[Done] = async {
    val plans = await(deploymentRepository.all())
    val badPlans = plans.collect {
      case plan: DeploymentPlan if plan.original.transitiveApps.exists(app => app.constraints.exists(isBrokenConstraint)) || // scalastyle:off
        plan.target.transitiveApps.exists(app => app.constraints.exists(isBrokenConstraint)) => plan
    }
    val futures = badPlans.map { plan =>
      log.info(s"Fixing plan ${plan.id} as it contains apps with invalid " +
        "constraints (even if they are not affected by the plan itself)")
      deploymentRepository.store(plan.copy(original = fixRoot(plan.original), target = fixRoot(plan.target)))
    }
    await(Future.sequence(futures))
    Done
  }

  def migrate(): Future[Done] = async {
    log.info("Starting migration to 1.3.5")
    await(Future.sequence(Seq(migrateApps(), migratePlans(), migrateRoot())))
    log.info("Migration to 1.3.5 complete")
    Done
  }
}

object StorageVersions {
  val VersionRegex = """^(\d+)\.(\d+)\.(\d+).*""".r

  def apply(major: Int, minor: Int, patch: Int): StorageVersion = {
    StorageVersion
      .newBuilder()
      .setMajor(major)
      .setMinor(minor)
      .setPatch(patch)
      .build()
  }

  def current: StorageVersion = {
    BuildInfo.version match {
      case VersionRegex(major, minor, patch) =>
        StorageVersions(
          major.toInt,
          minor.toInt,
          patch.toInt
        )
    }
  }

  implicit class OrderedStorageVersion(val version: StorageVersion) extends AnyVal with Ordered[StorageVersion] {
    override def compare(that: StorageVersion): Int = {
      def by(left: Int, right: Int, fn: => Int): Int = if (left.compareTo(right) != 0) left.compareTo(right) else fn
      by(version.getMajor, that.getMajor, by(version.getMinor, that.getMinor, by(version.getPatch, that.getPatch, 0)))
    }

    def str: String = s"Version(${version.getMajor}, ${version.getMinor}, ${version.getPatch})"

    def nonEmpty: Boolean = !version.equals(empty)
  }

  def empty: StorageVersion = StorageVersions(0, 0, 0)
}
