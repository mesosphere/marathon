package mesosphere.util.state.memory

import mesosphere.marathon.StoreCommandFailedException
import mesosphere.util.ThreadPoolContext
import mesosphere.util.state.{ PersistentEntity, PersistentStore }

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ ExecutionContext, Future }

/**
  * Simple in memory store implementation.
  * This is intended only for tests - do not use in production!
  * @param ec the execution context to use.
  */
class InMemoryStore(implicit val ec: ExecutionContext = ExecutionContext.Implicits.global) extends PersistentStore {

  private[this] val entities = TrieMap.empty[ID, InMemoryEntity]

  override def load(key: ID): Future[Option[PersistentEntity]] = Future.successful{
    entities.get(key)
  }

  override def create(key: ID, content: IndexedSeq[Byte]): Future[PersistentEntity] = Future {
    if (entities.contains(key)) throw new StoreCommandFailedException(s"Entity with id $key already exists!")
    val entity = InMemoryEntity(key, 0, content)
    entities.put(key, entity)
    entity
  }

  override def update(entity: PersistentEntity): Future[PersistentEntity] = Future {
    entity match {
      case e @ InMemoryEntity(id, version, _) =>
        val currentVersion = entities(id)
        if (currentVersion.version != version) throw new StoreCommandFailedException("Concurrent updates!")
        val nextVersion = e.withNextVersion
        if (entities.replace(id, currentVersion, nextVersion)) nextVersion
        else throw new StoreCommandFailedException(s"Concurrent updates!")
      case _ => throw new IllegalArgumentException(s"Wrong entity type: $entity")
    }
  }

  override def delete(key: ID): Future[Boolean] = {
    entities.get(key) match {
      case Some(value) => Future.successful(entities.remove(key).isDefined)
      case None        => Future.successful(false)
    }
  }

  override def allIds(): Future[Seq[ID]] = Future.successful(entities.keySet.toSeq)
}

case class InMemoryEntity(id: String, version: Int, bytes: IndexedSeq[Byte] = Vector.empty) extends PersistentEntity {
  override def withNewContent(bytes: IndexedSeq[Byte]): PersistentEntity = copy(bytes = bytes)
  def withNextVersion: InMemoryEntity = copy(version = version + 1)
}
