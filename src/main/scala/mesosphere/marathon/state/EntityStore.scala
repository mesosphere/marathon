package mesosphere.marathon.state

import scala.concurrent.Future

/**
  * The entity store is mostly syntactic sugar around the PersistentStore.
  * The main idea is to handle serializing/deserializing of specific entities.
  * @tparam T the specific type of entities that are handled by this specific store.
  */
trait EntityStore[T] {

  type Deserialize = () => T //returns deserialized T value.
  type Update = Deserialize => T //Update function Gets an Read and returns the (modified) T

  def fetch(key: String): Future[Option[T]]

  def store(key: String, value: T): Future[T] = modify(key)(_ => value)

  def modify(key: String, onSuccess: (T) => Unit = _ => ())(update: Update): Future[T]

  /**
    * Delete entity with given id.
    * Success: the file was deleted (true) or not existent (false)
    * Failure: the file could not be deleted
    * @param key the name of the entity.
    * @return result, whether the file was existent or not.
    */
  def expunge(key: String, onSuccess: () => Unit = () => ()): Future[Boolean]

  def names(): Future[Seq[String]]
}
