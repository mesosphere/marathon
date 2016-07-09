/*package mesosphere.marathon.core.storage.impl

import akka.http.scaladsl.marshalling.{ Marshal, Marshaller }
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.core.storage.{ BasePersistenceStore, IdResolver, PersistenceStore }
import mesosphere.marathon.util.toRichFuture

import scala.async.Async.{ async, await }
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

/**
  * A Write Ahead Cache of another persistence store that lazily loads values into the cache.
  *
  * @param store The store to cache
  * @param mat a materializer for Akka Streaming
  * @param ctx The execution context for future chaining.
  * @tparam K The persistence store's primary key type
  * @tparam Serialized The serialized format for the persistence store.
  */
class LazyCachingPersistenceStore[K, Serialized](
    store: BasePersistenceStore[K, Serialized])(implicit
  mat: ActorMaterializer,
    ctx: ExecutionContext) extends PersistenceStore[K, Serialized] with StrictLogging {

  // Cache for 'ids' - note: since we have no information about the layout of 'K', any
  // create/delete operation will invalidate the entire id cache.
  private[storage] val idCache = TrieMap[K, Seq[K]]()
  private[storage] val valueCache = TrieMap[K, Option[Any]]()

  override def ids[Id, V](parent: Id)(implicit ir: IdResolver[Id, K, V, Serialized]): Source[Id, NotUsed] = {
    val storeId = ir.toStorageId(parent)
    if (idCache.contains(storeId)) {
      Source(idCache(storeId).map(ir.fromStorageId))
    } else {
      val childrenFuture = async {
        val children = await(store.ids(parent).toMat(Sink.seq)(Keep.right).run())
        idCache(storeId) = children.map(ir.toStorageId)
        children
      }
      Source.fromFuture(childrenFuture).mapConcat(identity)
    }
  }

  override def create[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, K, V, Serialized],
    m: Marshaller[V, Serialized],
    um: Unmarshaller[Serialized, V]): Future[Done] = {
    val storeId = ir.toStorageId(id)
    async {
      await(store.create(id, v).asTry) match {
        case Success(_) =>
          valueCache(storeId) = Some(v)
          idCache.clear()
          Done
        case Failure(e) =>
          throw e
      }
    }
  }

  override def update[Id, V](id: Id)(change: (V) => Try[V])(implicit
    ir: IdResolver[Id, K, V, Serialized],
    um: Unmarshaller[Serialized, V],
    m: Marshaller[V, Serialized]): Future[V] = {
    val storeId = ir.toStorageId(id)
    async {
      await(get(id)) match {
        case Some(old) =>
          change(old) match {
            case Success(newValue) =>
              val serialized = await(Marshal(newValue).to[Serialized])
              await(store.rawSet(storeId, serialized).asTry) match {
                case Success(_) =>
                  valueCache(storeId) = Some(newValue)
                  old
                case Failure(x) =>
                  throw x
              }
            case Failure(e) =>
              old
          }
        case None =>
          throw new StoreCommandFailedException(s"Unable to update $id as it doesn't exist")
      }
    }
  }

  override def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, K, V, Serialized],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = {
    val storeId = ir.toStorageId(id)
    if (valueCache.contains(storeId)) {
      Future.successful(valueCache(storeId).asInstanceOf[Option[V]])
    } else {
      async {
        val storedValue = await(store.get(id))
        valueCache.put(storeId, storedValue)
        storedValue
      }
    }
  }

  override def delete[Id, V](k: Id)(implicit ir: IdResolver[Id, K, V, Serialized]): Future[Done] = {
    val storeId = ir.toStorageId(k)
    async {
      await(store.delete(k).asTry) match {
        case Success(_) =>
          idCache.clear()
          valueCache.remove(storeId)
          Done
        case Failure(e) =>
          throw e
      }
    }
  }

  override protected[storage] def keys(): Source[K, NotUsed] = {
    logger.warn(s"keys() called on a CachingPersistenceStore (which is what keys() is for), deferring to $store")
    store.keys()
  }

  override def toString: String = s"LazyCachingPersistenceStore($store)"
}
*/