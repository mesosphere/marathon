package mesosphere.marathon.state

import com.google.protobuf.InvalidProtocolBufferException
import org.apache.mesos.state.State
import scala.collection.JavaConverters._
import scala.concurrent._
import mesosphere.marathon.StorageException
import mesosphere.util.LockManager
import mesosphere.util.{ ThreadPoolContext, BackToTheFuture }
import mesosphere.marathon.Protos.StorageVersion
import scala.concurrent.duration.Duration
import org.slf4j.LoggerFactory
import scala.util.Try

class MarathonStore[S <: MarathonState[_, S]](
    state: State,
    newState: () => S,
    prefix: String = "app:",
    implicit val timeout: BackToTheFuture.Timeout = BackToTheFuture.Implicits.defaultTimeout)(implicit val migration: Migration[S]) extends PersistenceStore[S] {

  import ThreadPoolContext.context
  import BackToTheFuture.futureToFutureOption

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] lazy val locks = LockManager[String]()

  val migrationRes = version.flatMap { ver =>
    if (migration.needsMigration(ver)) {
      log.info(s"Need to migrate ${getClass.getName}")
      names().flatMap { names =>
        val migrations = names.map { name =>
          modify(name) { s =>
            migration.migrate(ver, s())
          }
        }

        Future.sequence(migrations).map(_.forall(_.isDefined))
      }

    }
    else {
      Future.successful(true)
    }
  } flatMap { success =>
    assert(success, "Storage migration failed")
    storeCurrentVersion()
  }

  Await.result(migrationRes, Duration.Inf)

  def fetch(key: String): Future[Option[S]] = {
    state.fetch(prefix + key) map {
      case Some(variable) => stateFromBytes(variable.value)
      case None           => throw new StorageException(s"Failed to read $key")
    }
  }

  def modify(key: String)(f: (() => S) => S): Future[Option[S]] = {
    val lock = locks.get(key)
    lock.acquire()

    val res = state.fetch(prefix + key) flatMap {
      case Some(variable) =>
        val deserialize = () => stateFromBytes(variable.value).getOrElse(newState())
        state.store(variable.mutate(f(deserialize).toProtoByteArray)) map {
          case Some(newVar) => stateFromBytes(newVar.value)
          case None         => throw new StorageException(s"Failed to store $key")
        }
      case None => throw new StorageException(s"Failed to read $key")
    }

    res onComplete { _ =>
      lock.release()
    }

    res
  }

  def expunge(key: String): Future[Boolean] = {
    val lock = locks.get(key)
    lock.acquire()

    val res = state.fetch(prefix + key) flatMap {
      case Some(variable) =>
        state.expunge(variable) map {
          case Some(b) => b.booleanValue()
          case None    => throw new StorageException(s"Failed to expunge $key")
        }

      case None => throw new StorageException(s"Failed to read $key")
    }

    res onComplete { _ =>
      lock.release()
    }

    res
  }

  def names(): Future[Iterator[String]] = {
    // TODO use implicit conversion after it has been merged
    future {
      try {
        state.names().get.asScala.collect {
          case name if name startsWith prefix =>
            name.replaceFirst(prefix, "")
        }
      }
      catch {
        // Thrown when node doesn't exist
        case e: ExecutionException => Seq().iterator
      }
    }
  }

  def version: Future[StorageVersion] = state.fetch(s"__internal__:${prefix}storage:version").map {
    case Some(variable) =>
      Try(StorageVersion.parseFrom(variable.value())).getOrElse(StorageVersions.empty)
    case None => throw new StorageException("Failed to read storage version")
  }

  private def stateFromBytes(bytes: Array[Byte]): Option[S] = {
    try {
      Some(newState().mergeFromProto(bytes))
    }
    catch {
      case e: InvalidProtocolBufferException => None
    }
  }

  protected def storeCurrentVersion(): Future[StorageVersion] = {
    state.fetch(s"__internal__:${prefix}storage:version") flatMap {
      case Some(variable) =>
        state.store(variable.mutate(StorageVersions.current.toByteArray)) map {
          case Some(newVar) => StorageVersion.parseFrom(newVar.value)
          case None         => throw new StorageException(s"Failed to store storage version")
        }

      case None => throw new StorageException("Failed to read storage version")
    }
  }
}
