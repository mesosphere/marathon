package mesosphere.marathon.state

import scala.concurrent.Future

/**
 * @author Tobi Knaup
 */

trait PersistenceStore[T] {

  def fetch(key: String): Future[Option[T]]

  def store(key: String, value: T): Future[Option[T]] = modify(key)(_ => value)

  // () => T: returns deserialized T value.
  // user can avoid unnecessary deserialization calls.
  def modify(key:String)(f: (() => T) => T): Future[Option[T]]

  def expunge(key: String): Future[Boolean]

  def names(): Future[Iterator[String]]

}
