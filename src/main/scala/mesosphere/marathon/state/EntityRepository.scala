package mesosphere.marathon.state


import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import mesosphere.marathon.StorageException


trait EntityRepository[T<:MarathonState[_, T]] {

  def store:PersistenceStore[T]

  protected val ID_DELIMITER = ":"

  val defaultWait = store match {
    case m: MarathonStore[_] => m.defaultWait
    case _ => Duration(3, SECONDS)
  }

  /**
   * Returns the most recently stored entity with the supplied id.
   */
  def currentVersion(id: String): Future[Option[T]] =
    this.store.fetch(id)

  /**
   * Returns the entity with the supplied id and version.
   */
  def entity(id: String, version: Timestamp): Future[Option[T]] = {
    val key = id + ID_DELIMITER + version.toString
    this.store.fetch(key)
  }

  /**
   * Returns the id for all entities.
   */
  def allIds(): Future[Iterable[String]] =
    this.store.names().map { names =>
      names.collect {
        case name: String if !name.contains(ID_DELIMITER) => name
      }.toSeq
    }

  /**
   * Returns the current version for all entities.
   */
  def current(): Future[Iterable[T]] =
    allIds().flatMap { names =>
      Future.sequence(names.map { name =>
      	currentVersion(name)
      }).map { _.flatten }
    }

  /**
   * Returns the timestamp of each stored version of the entity with the supplied id.
   */
  def listVersions(id: String): Future[Iterable[Timestamp]] = {
    val prefix = id + ID_DELIMITER
  	this.store.names().map { names =>
  	  names.collect {
  	    case name: String if name.startsWith(prefix) =>
          Timestamp(name.substring(prefix.length))
  	  }.toSeq
  	}
  }

  /**
   * Deletes all versions of the entity with the supplied id.
   */
  def expunge(id: String): Future[Iterable[Boolean]] =
    listVersions(id).flatMap { timestamps =>
      val versionsDeleteResult = timestamps.map { timestamp =>
      	val key = id + ID_DELIMITER + timestamp.toString
      	store.expunge(key)
      }
      val currentDeleteResult = store.expunge(id)
      Future.sequence(currentDeleteResult +: versionsDeleteResult.toSeq)
    }

  protected def storeWithVersion(id: String, version: Timestamp, t: T): Future[T] = {
    for {
      alias <- this.store.store(id, t) if alias.isDefined
      result <- this.store.store(id + ID_DELIMITER + version, t)
    } yield result.getOrElse(throw new StorageException(s"Can not persist $t"))
  }
}
