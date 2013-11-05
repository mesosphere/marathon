package mesosphere.marathon.state

import scala.concurrent.Future

/**
 * @author Tobi Knaup
 */

trait PersistenceStore[T] {

  def fetch(key: String): Future[Option[T]]

  def store(key: String, value: T): Future[Option[T]]

  def expunge(key: String): Future[Option[Boolean]]

  def names(): Future[Iterator[String]]

}
