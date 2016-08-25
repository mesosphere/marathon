package mesosphere.marathon.storage.migration.legacy.legacy

import java.io.{ ByteArrayInputStream, ObjectInputStream }

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.MarathonTaskState
import mesosphere.marathon.storage.LegacyStorageConfig
import mesosphere.marathon.storage.repository.TaskRepository
import mesosphere.marathon.storage.repository.legacy.TaskEntityRepository
import mesosphere.marathon.storage.repository.legacy.store.PersistentStore
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

class MigrationTo0_13(legacyStorageConfig: Option[LegacyStorageConfig])(implicit
  ctx: ExecutionContext,
    metrics: Metrics) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  // the bytes stored via TaskTracker are incompatible to EntityRepo, so we have to parse them 'manually'
  def fetchLegacyTask(store: PersistentStore, taskKey: String): Future[Option[MarathonTask]] = {
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

  def migrateTasks(persistentStore: PersistentStore, taskRepository: TaskEntityRepository): Future[Unit] = {
    log.info("Start 0.13 migration")

    taskRepository.store.names().flatMap { keys =>
      log.info("Found {} tasks in store", keys.size)
      // old format is appId:appId.taskId
      val oldFormatRegex = """^.*:.*\..*$""".r
      val namesInOldFormat = keys.filter(key => oldFormatRegex.pattern.matcher(key).matches)
      log.info("{} tasks in old format need to be migrated.", namesInOldFormat.size)

      namesInOldFormat.foldLeft(Future.successful(())) { (f, nextKey) =>
        f.flatMap(_ => migrateKey(persistentStore, taskRepository, nextKey))
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
  private[migration] def migrateKey(
    store: PersistentStore,
    taskRepository: TaskEntityRepository,
    legacyKey: String): Future[Unit] = {
    fetchLegacyTask(store, legacyKey).flatMap {
      case Some(task) =>
        taskRepository.store(TaskSerializer.fromProto(task)).flatMap { _ =>
          taskRepository.store.expunge(legacyKey).map(_ => ())
        }
      case _ => Future.failed[Unit](new RuntimeException(s"Unable to load entity with key = $legacyKey"))
    }
  }

  def renameFrameworkId(store: PersistentStore): Future[Unit] = {
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

  def migrate(): Future[Unit] =
    legacyStorageConfig.fold(Future.successful(())) { config =>
      val taskRepo = TaskRepository.legacyRepository(config.entityStore[MarathonTaskState])
      val store = config.store

      for {
        _ <- migrateTasks(store, taskRepo)
        _ <- renameFrameworkId(store)
      } yield ()
    }
}

