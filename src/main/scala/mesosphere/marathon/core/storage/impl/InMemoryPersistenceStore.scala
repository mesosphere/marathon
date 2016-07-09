/*package mesosphere.marathon.core.storage.impl

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.core.storage.{ BasePersistenceStore, IdResolver }

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

case class RamId(id: String)
case class Identity(value: Any)

trait InMemoryStoreSerialization {
  implicit def idResolver[V]: IdResolver[String, RamId, V, Identity] =
    new IdResolver[String, RamId, V, Identity] {
      override def toStorageId(id: String): RamId = RamId(id)
      override def fromStorageId(key: RamId): String = key.id
    }

  implicit def marshaller[V]: Marshaller[V, Identity] = Marshaller.opaque { a: V => Identity(a) }

  implicit def unmarshaller[V]: Unmarshaller[Identity, V] =
    Unmarshaller.strict { a: Identity => a.value.asInstanceOf[V] }
}

class InMemoryPersistenceStore(implicit protected val mat: ActorMaterializer)
    extends BasePersistenceStore[RamId, Identity] {
  val entries = TrieMap[RamId, Identity]()

  override protected[storage] def keys(): Source[RamId, NotUsed] = {
    @tailrec def allPaths(path: String, remaining: List[String], all: List[String]): List[String] = {
      val root = if (path.isEmpty) path else s"$path/"
      remaining match {
        case head :: tail =>
          allPaths(s"$root$head", tail, s"$root$head" :: all)
        case Nil =>
          all
      }
    }
    val keys = entries.keys.flatMap { key =>
      allPaths("", key.id.split("/").toList, Nil)
    }
    Source(keys.map(RamId(_))(collection.breakOut))
  }

  override protected def rawDelete(id: RamId): Future[Done] = {
    entries.remove(id)
    Future.successful(Done)
  }

  override protected[storage] def rawGet(id: RamId): Future[Option[Identity]] = {
    Future.successful(entries.get(id))
  }

  override protected[storage] def rawSet(id: RamId, v: Identity): Future[Done] = {
    if (entries.contains(id)) {
      entries.put(id, v)
      Future.successful(Done)
    } else {
      Future.failed(new StoreCommandFailedException(s"Attempted to set '${id.id}' = $v, but the entry didn't exist"))
    }
  }

  override protected def rawIds(parent: RamId): Source[RamId, NotUsed] = {
    Source(entries.keySet.withFilter(_.id.startsWith(parent.id)).map { key =>
      val path = if (parent.id == "") key.id else key.id.replaceAll(s"^(${parent.id})/", "")
      RamId(path.split("/").head)
    }(collection.breakOut))
  }

  override protected def rawCreate(id: RamId, v: Identity): Future[Done] = {
    if (entries.contains(id)) {
      Future.failed(
        new StoreCommandFailedException(s"Attempted to create '${id.id}' = $v, but the entry already exists"))
    } else {
      entries.put(id, v)
      Future.successful(Done)
    }
  }
}

*/