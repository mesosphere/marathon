package mesosphere.marathon.state

import scala.concurrent.Future

trait EntityRepository[T <: MarathonState[_, T]] extends StateMetrics with VersionedEntry {
  import scala.concurrent.ExecutionContext.Implicits.global

  protected def store: EntityStore[T]
  protected def maxVersions: Option[Int]

  /**
    * Returns the most recently stored entity with the supplied id.
    */
  protected def currentVersion(id: String): Future[Option[T]] =
    timedRead { this.store.fetch(id) }

  /**
    * Returns the entity with the supplied id and version.
    */
  protected def entity(id: String, version: Timestamp): Future[Option[T]] = timedRead {
    this.store.fetch(versionKey(id, version))
  }

  /**
    * Returns the id for all entities.
    */
  def allIds(): Future[Iterable[String]] = timedRead {
    this.store.names().map { names =>
      names.collect {
        case name: String if noVersionKey(name) => name
      }
    }
  }

  /**
    * Returns the current version for all entities.
    */
  protected def current(): Future[Iterable[T]] = timedRead {
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
    val prefix = versionKeyPrefix(id)
    this.store.names().map { names =>
      names.collect {
        case name: String if name.startsWith(prefix) =>
          Timestamp(name.substring(prefix.length))
      }.sorted.reverse
    }
  }

  /**
    * Deletes all versions of the entity with the supplied id.
    */
  def expunge(id: String): Future[Iterable[Boolean]] = timedWrite {
    listVersions(id).flatMap { timestamps =>
      val versionsDeleteResult = timestamps.map { timestamp =>
        store.expunge(versionKey(id, timestamp))
      }
      val currentDeleteResult = store.expunge(id)
      Future.sequence(currentDeleteResult +: versionsDeleteResult.toSeq)
    }
  }

  private[this] def limitNumberOfVersions(id: String): Future[Iterable[Boolean]] = {
    val maximum = maxVersions.map { maximum =>
      listVersions(id).flatMap { versions =>
        Future.sequence(versions.drop(maximum).map(version => store.expunge(versionKey(id, version))))
      }
    }
    maximum.getOrElse(Future.successful(Nil))
  }

  protected def storeWithVersion(id: String, version: Timestamp, t: T): Future[T] = {
    for {
      alias <- storeByName(id, t)
      result <- storeByName(versionKey(id, version), t)
      limit <- limitNumberOfVersions(id)
    } yield result
  }

  /**
    * Stores the given entity directly under the given id without a second versioned store.
    */
  protected def storeByName(id: String, t: T): Future[T] = timedWrite {
    this.store.store(id, t)
  }
}
