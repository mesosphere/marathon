package mesosphere.marathon.state

import mesosphere.marathon.StorageException
import scala.concurrent.Future

trait EntityRepository[T <: MarathonState[_, T]] extends StateMetrics {
  import mesosphere.util.ThreadPoolContext.context

  def store: PersistenceStore[T]
  def maxVersions: Option[Int]

  protected val ID_DELIMITER = ":"

  /**
    * Returns the most recently stored entity with the supplied id.
    */
  def currentVersion(id: String): Future[Option[T]] =
    timedRead { this.store.fetch(id) }

  /**
    * Returns the entity with the supplied id and version.
    */
  def entity(id: String, version: Timestamp): Future[Option[T]] = timedRead {
    val key = id + ID_DELIMITER + version.toString
    this.store.fetch(key)
  }

  /**
    * Returns the id for all entities.
    */
  def allIds(): Future[Iterable[String]] = timedRead {
    this.store.names().map { names =>
      names.collect {
        case name: String if !name.contains(ID_DELIMITER) => name
      }.toSeq
    }
  }

  /**
    * Returns the current version for all entities.
    */
  def current(): Future[Iterable[T]] = timedRead {
    allIds().flatMap { names =>
      Future.sequence(names.map { name =>
        currentVersion(name)
      }).map { _.flatten }
    }
  }

  /**
    * Returns the timestamp of each stored version of the entity with the supplied id.
    */
  def listVersions(id: String): Future[Iterable[Timestamp]] = timedRead {
    val prefix = id + ID_DELIMITER
    this.store.names().map { names =>
      names.collect {
        case name: String if name.startsWith(prefix) =>
          Timestamp(name.substring(prefix.length))
      }.toSeq.sorted.reverse
    }
  }

  /**
    * Deletes all versions of the entity with the supplied id.
    */
  def expunge(id: String): Future[Iterable[Boolean]] = timedWrite {
    listVersions(id).flatMap { timestamps =>
      val versionsDeleteResult = timestamps.map { timestamp =>
        val key = id + ID_DELIMITER + timestamp.toString
        store.expunge(key)
      }
      val currentDeleteResult = store.expunge(id)
      Future.sequence(currentDeleteResult +: versionsDeleteResult.toSeq)
    }
  }

  def limitNumberOfVersions(id: String): Future[Iterable[Boolean]] = timedWrite {
    val maximum = maxVersions.map { maximum =>
      listVersions(id).flatMap { versions =>
        Future.sequence(versions.drop(maximum).map(version => store.expunge(id + ID_DELIMITER + version)))
      }
    }
    maximum.getOrElse(Future.successful(Nil))
  }

  protected def storeWithVersion(id: String, version: Timestamp, t: T): Future[T] = {
    for {
      alias <- this.store.store(id, t) if alias.isDefined
      result <- this.store.store(id + ID_DELIMITER + version, t)
      limit <- limitNumberOfVersions(id)
    } yield result.getOrElse(throw new StorageException(s"Can not persist $t"))
  }
}
