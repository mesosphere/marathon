package mesosphere.marathon
package core.storage.repository

import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }

import scala.concurrent.Future

/** Repository that can store exactly one value of T */
trait SingletonRepository[T] {
  def get(): Future[Option[T]]
  def store(v: T): Future[Done]
  def delete(): Future[Done]
}

/**
  * A Repository of values (T) identified uniquely by (Id)
  */
trait ReadOnlyRepository[Id, T] {
  def ids(): Source[Id, NotUsed]
  def all(): Source[T, NotUsed]
  def get(id: Id): Future[Option[T]]
}

/**
  * A Repository of values (T) identified uniquely by (Id)
  */
trait Repository[Id, T] extends ReadOnlyRepository[Id, T] {
  def store(v: T): Future[Done]
  def delete(id: Id): Future[Done]
}

/**
  * A Repository of versioned values (T) identified uniquely by (Id)
  */
trait ReadOnlyVersionedRepository[Id, T] extends ReadOnlyRepository[Id, T] {
  def versions(id: Id): Source[OffsetDateTime, NotUsed]
  def getVersion(id: Id, version: OffsetDateTime): Future[Option[T]]
  def getVersions(list: Seq[(Id, OffsetDateTime)]): Source[T, NotUsed]
}

/**
  * A Repository of versioned values (T) identified uniquely by (Id)
  */
trait VersionedRepository[Id, T] extends ReadOnlyVersionedRepository[Id, T] with Repository[Id, T] {
  def storeVersion(v: T): Future[Done]
  // Removes _only_ the current value, leaving all history in place.
  def deleteCurrent(id: Id): Future[Done]
}
