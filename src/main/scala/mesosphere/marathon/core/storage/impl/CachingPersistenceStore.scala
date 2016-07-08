package mesosphere.marathon.core.storage.impl

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.{PrePostDriverCallback, StoreCommandFailedException}
import mesosphere.marathon.core.storage.{IdResolver, PersistenceStore}
import mesosphere.marathon.util.toRichFuture

import scala.async.Async.{async, await}
import scala.collection.concurrent.TrieMap
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * A Write Ahead Cache of another persistence store that preloads the entire persistence store into memory before
  * satisfying any requests.
  *
  * TODO: Consider an alternative strategy where we see if the promise is complete and use it
  * otherwise going directly to the storage layer. This turns out to be much more complicated
  * as the cache is populated asynchronously, so it would have to queue up all create/update operations
  * onto the future to keep the value fully updated: then, there would be a short window of time when
  * the cached data is actually stale.
  *
  * @param store The store to cache
  * @param keyAsPath Conversion from the Persistence layer's key to a stringified path.
  * @param mat a materializer for akka streaming
  * @tparam Serialized The serialized format for the persistence store.
  */
class CachingPersistenceStore[K, Serialized](
    store: PersistenceStore[K, Serialized],
    keyAsPath: K => String,
    pathAsKey: String => K)(
    implicit
    override val mat: Materializer
) extends PersistenceStore[K, Serialized] with StrictLogging with PrePostDriverCallback {

  private[storage] val cache = Promise[TrieMap[String, Either[Serialized, Any]]]()

  override def preDriverStarts: Future[Unit] = {
    val storage = TrieMap[String, Either[Serialized, Any]]()
    val future = store.keys().mapAsync(8) { key =>
      store.rawGet(key).map(v => key -> v)
    }.runForeach {
      case (key, maybeSerialized) =>
        maybeSerialized match {
          case Some(v) =>
            storage.put(keyAsPath(key), Left(v))
          case None =>
        }
    }
    cache.completeWith(future.map(_ => storage))
    future.map(_ => ())
  }

  override def postDriverTerminates: Future[Unit] = Future.successful(())

  override def ids[Id, V](parent: Id)(implicit ir: IdResolver[Id, K, V, Serialized]): Source[Id, NotUsed] = {
    val future = async {
      val cached = await(cache.future)

      val storageId = keyAsPath(ir.toStorageId(parent))
      val result = cached.keySet.withFilter(_.startsWith(storageId)).map { key =>
        val path = if (storageId == "") storageId else key.replaceAll(s"$storageId/", "")
        path.split("/").head
      }
      result
    }
    Source.fromFuture(future).mapConcat(_.map(k => ir.fromStorageId(pathAsKey(k)))(collection.breakOut))
  }

  override def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, K, V, Serialized],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = async {
    val cached = await(cache.future)
    val stored = cached.get(keyAsPath(ir.toStorageId(id)))
    stored match {
      case Some(Left(serialized)) =>
        val deserialized = await(um(serialized))
        cached.put(keyAsPath(ir.toStorageId(id)), Right(deserialized))
        Some(deserialized)
      case Some(Right(deserialized)) =>
        Some(deserialized.asInstanceOf[V])
      case None =>
        None
    }
  }

  override def create[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, K, V, Serialized],
    m: Marshaller[V, Serialized],
    um: Unmarshaller[Serialized, V]): Future[Done] = {
    async {
      await(store.create(id, v).asTry) match {
        case Success(_) =>
          await(cache.future).put(keyAsPath(ir.toStorageId(id)), Right(v))
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
    async {
      await(get(id)) match {
        case Some(old) =>
          change(old) match {
            case Success(newValue) =>
              val serialized = await(m(newValue))
              await(cache.future).put(keyAsPath(ir.toStorageId(id)), Right(serialized))
              old
            case Failure(e) =>
              old
          }
        case None =>
          throw new StoreCommandFailedException(s"Unable to update $id as it doesn't exist")
      }
    }
  }

  override def delete[Id, V](k: Id)(implicit ir: IdResolver[Id, K, V, Serialized]): Future[Done] = {
    async {
      await(store.delete(k).asTry) match {
        case Success(_) =>
          await(cache.future).remove(keyAsPath(ir.toStorageId(k)))
          Done
        case Failure(e) =>
          throw e
      }
    }
  }

  override def toString: String = s"CachingPersistenceStore($store)"

  // None of the raw methods need to be implemented.

  override protected[storage] def rawGet(id: K): Future[Option[Serialized]] = ???

  override protected def rawSet(id: K, v: Serialized): Future[Done] = ???

  override protected def rawDelete(id: K): Future[Done] = ???

  override protected def rawIds(parent: K): Source[K, NotUsed] = ???

  override protected def rawCreate(id: K, v: Serialized): Future[Done] = ???

  override protected[storage] def keys(): Source[K, NotUsed] = {
    logger.warn(s"keys() called on a CachingPersistenceStore (which is what keys() is for), deferring to $store")
    store.keys()
  }
}
