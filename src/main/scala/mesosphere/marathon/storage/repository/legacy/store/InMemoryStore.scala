package mesosphere.marathon
package storage.repository.legacy.store

import akka.Done

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }

/**
  * Simple in memory store implementation.
  * This is intended only for tests - do not use in production!
  * @param ec the execution context to use.
  */
class InMemoryStore(implicit val ec: ExecutionContext = ExecutionContext.Implicits.global) extends PersistentStore {

  private[this] val entities = TrieMap.empty[ID, InMemoryEntity]

  override def load(key: ID): Future[Option[PersistentEntity]] = {
    require(isOpen, "the store must be opened before it can be used")

    Future.successful {
      entities.get(key)
    }
  }

  override def create(key: ID, content: IndexedSeq[Byte]): Future[PersistentEntity] = {
    require(isOpen, "the store must be opened before it can be used")

    Future {
      if (entities.contains(key)) throw new StoreCommandFailedException(s"Entity with id $key already exists!")
      val entity = InMemoryEntity(key, 0, content)
      entities.put(key, entity)
      entity
    }
  }

  override def update(entity: PersistentEntity): Future[PersistentEntity] = {
    require(isOpen, "the store must be opened before it can be used")

    Future {
      entity match {
        case e @ InMemoryEntity(id, version, _) =>
          val currentVersion = entities(id)
          if (currentVersion.version != version) throw new StoreCommandFailedException("Concurrent updates!")
          val nextVersion = e.withNextVersion
          if (entities.replace(id, currentVersion, nextVersion)) nextVersion
          else throw new StoreCommandFailedException("Concurrent updates!")
        case _ => throw new IllegalArgumentException(s"Wrong entity type: $entity")
      }
    }
  }

  override def delete(key: ID): Future[Boolean] = {
    require(isOpen, "the store must be opened before it can be used")

    entities.get(key) match {
      case Some(value) => Future.successful(entities.remove(key).isDefined)
      case None => Future.successful(false)
    }
  }

  override def allIds(): Future[Seq[ID]] = {
    require(isOpen, "the store must be opened before it can be used")

    Future.successful(entities.keySet.toIndexedSeq)
  }

  override def sync(): Future[Done] = Future.successful(Done)
}

case class InMemoryEntity(id: String, version: Int, bytes: IndexedSeq[Byte] = Vector.empty) extends PersistentEntity {
  override def withNewContent(bytes: IndexedSeq[Byte]): PersistentEntity = copy(bytes = bytes)
  def withNextVersion: InMemoryEntity = copy(version = version + 1)
}
