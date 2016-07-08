package mesosphere.marathon.core.storage

import akka.http.scaladsl.marshalling.{ Marshal, Marshaller }
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.StoreCommandFailedException
import mesosphere.util.CallerThreadExecutionContext

import scala.async.Async._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

/**
  * Resolver for Marathon Internal Persistence IDs (by `Key Type`, `Value Type` and `Serialized Type`)
  * For example, Applications may be identified by the string "/apps/abc" and stored internally at
  * "/apps/a/a58ec17d-735d-4c3f-9aa8-d44c764aa31b"
  *
  * This IdResolver must be provided for all storage engines, e.g.
  *
  * {{{
  *   class A(val id: String) {}
  *   object A {
  *     implicit val zkIdResolver = new IdResolver[String, A, ByteString] = {
  *       def toStorageId(id: String): String = id
  *       def fromStorageId(id: String): String = id
  *   }
  * }}}
  *
  * @tparam Id The marathon type of the ID for the given Value type
  * @tparam K The persisted type of the ID
  * @tparam V The value type being stored
  * @tparam Serialized The serialized type of 'V' for the given [[PersistenceStore]].
  */
trait IdResolver[Id, K, +V, +Serialized] {
  /**
    * Translate the marathon id into the given persisted format
    */
  def toStorageId(id: Id): K

  /**
    * Translate from the persisted format to the marathon id.
    */
  def fromStorageId(key: K): Id
}

/**
  * Generic Persistence Store with flexible storage backends using Akka Marshalling Infrastructure.
  *
  * == Providing Serialization for a given class ==
  *  - Provide an [[IdResolver]] for your class for the supported [[PersistenceStore]]s
  *  - Provide a Marshaller and Unmarshaller for
  *    your class and the Serialized form for the supported [[PersistenceStore]]s.
  *  - For example, given a class 'A', a K set to [[mesosphere.marathon.core.storage.impl.zk.ZkId]]
  *    and Serialized as [[mesosphere.marathon.core.storage.impl.zk.ZkSerialized]],
  *    the following implicits should be sufficient
  * {{{
  *   case class A(id: Int, name: String)
  *   object A {
  *     implicit val zkIdResolver = new IdResolver[Int, ZkId, A, ZkSerialized] {
  *       def toStorageId(id: Int): ZkId =
  *         ZkId("/A/" + id.toString) // note: scaladoc bug where string interpolation fails
  *       def fromStorageId(key: ZkId): Int = key.value.replaceAll("/A/", "").toInt
  *     }
  *     implicit val zkMarshaller = Marshaller[A, ZkSerialized] =
  *       Marshaller.opaque { (a: A) =>
  *         val bytes = ByteString.newBuilder
  *         bytes.putInt(a.id)
  *         val strBytes = a.name.getBytes(StandardCharsets.UTF_8)
  *         bytes.putInt(strBytes.length)
  *         bytes.putBytes(strBytes)
  *         ZkSerialized(bytes.result)
  *       }
  *     implicit val zkUnmarshaller = Unmarshaller.strict { (zk: ZkSerialized) =>
  *       val it = zk.bytes.iterator
  *       val id = it.getInt
  *       val strLen = it.getInt
  *       val str = new String(it.getBytes(strlen, StandardCharsets.UTF_8))
  *       A(id, str)
  *     }
  *   }
  * }}}
  *
  * == Notes for implementing new subclasses ==
  *  - A Large amount of the infrastructure is already provided in the base trait, especially
  *    marshalling and unmarshalling
  *  - Disambiguate the Key and Serialization types when possible,
  *     - e.g. ZkId(String) instead of String, unless they are truly generic,
  *     - e.g. com.google.protobuf.Message can generally be used almost anywhere
  *     that can serialize and deserialize bytes.
  *  - Wrap underlying storage errors in [[StoreCommandFailedException]], but leave other exceptions as is.
  *  - Use [[mesosphere.marathon.util.Retry]] - storage layers may have network connectivity issues.
  *  - Ensure your unit test uses the test cases in PersistenceStoreTest and passes all of them.
  *    You may also want to add additional test cases for connectivity.
  *  - Add the type conversions for serialized types, either to their companion object
  *    or within the impl package for your storage layer as appropriate.
  *
  * @tparam K The persistence store's primary key type
  * @tparam Serialized The serialized format for the persistence store.
  */
trait PersistenceStore[K, Serialized] {
  implicit protected val mat: Materializer
  implicit protected val ctx: ExecutionContext = ExecutionContext.global

  /**
    * Get a list of all immediate children under the given parent.
    */
  def ids[Id, V](parent: Id)(implicit ir: IdResolver[Id, K, V, Serialized]): Source[Id, NotUsed] = {
    rawIds(ir.toStorageId(parent)).map(ir.fromStorageId)
  }

  /**
    * Get the data, if any, for the given primary id and value type.
    *
    * @return A future representing the data at the given Id, if any exists.
    *         If there is an underlying storage problem, the future should fail with [[StoreCommandFailedException]]
    */
  def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, K, V, Serialized],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = {

    async {
      await(rawGet(ir.toStorageId(id))) match {
        case Some(v) =>
          Some(await(um(v)))
        case None =>
          None
      }
    }
  }

  /**
    * Create the value for the given Id, failing if it already exists
    *
    * @return A Future that will complete once the value has been created at the given id, or fail with
    *         [[StoreCommandFailedException]] if either a value already exists at the given Id, or
    *         if there is an underlying storage problem
    */
  def create[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, K, V, Serialized],
    m: Marshaller[V, Serialized],
    um: Unmarshaller[Serialized, V]): Future[Done] = {
    createOrUpdate(id, (oldValue: Option[V]) => oldValue match {
      case None => Success(v)
      case Some(existing) =>
        Failure(new StoreCommandFailedException(s"Unable to create $id as it already exists ($existing)"))
    }).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)
  }

  /**
    * Update the value at the given Id if and only if the 'change' method returns a [[scala.util.Try]], if
    * the try is successful, the value will be updated.
    * A value must already exist at the given id.
    *
    * @return A Future that will complete with the previous value if there was already an
    *         existing value, even if the change
    *         callback fails.
    *         Any other condition should fail the future with [[StoreCommandFailedException]].
    */
  def update[Id, V](id: Id)(change: V => Try[V])(implicit
    ir: IdResolver[Id, K, V, Serialized],
    um: Unmarshaller[Serialized, V],
    m: Marshaller[V, Serialized]): Future[V] = {
    createOrUpdate(id, (oldValue: Option[V]) => oldValue match {
      case Some(old) =>
        change(old)
      case None =>
        Failure(new StoreCommandFailedException(s"Unable to update $id as it doesn't exist"))
    }).map(_.get)(CallerThreadExecutionContext.callerThreadExecutionContext)
  }

  /**
    * Delete the value at the given Id, idempotent
    *
    * @return A future indicating whether the value was deleted (or simply didn't exist). Underlying storage issues
    *         will fail the future with [[StoreCommandFailedException]]
    */
  def delete[Id, V](k: Id)(implicit ir: IdResolver[Id, K, V, Serialized]): Future[Done] = {
    rawDelete(ir.toStorageId(k))
  }

  /**
    * @return A source of _all_ keys in the Persistence Store (which can be used by a
    *         [[mesosphere.marathon.core.storage.impl.CachingPersistenceStore]] to populate the
    *         cache completely on startup.
    */
  protected[storage] def keys(): Source[K, NotUsed]

  /**
    * Delete the value at the given id.
    * @return A future that completes when the value was removed or didn't exist.
    *         The future may fail with a [[StoreCommandFailedException]] if there is an underlying storage issue.
    */
  protected def rawDelete(id: K): Future[Done]

  /**
    * Get a list of all immediate children under the given parent.
    */
  protected def rawIds(parent: K): Source[K, NotUsed]

  /**
    * Get the data, if any, for the given primary id and value type.
    *
    * @return A future representing the data at the given Id, if any exists.
    *         If there is an underlying storage problem, the future should fail with [[StoreCommandFailedException]]
    */
  protected[storage] def rawGet(id: K): Future[Option[Serialized]]

  /**
    * Set the value at the given id.
    *
    * @return A future that completes when the value has been updated to the new value
    *         or fail with [[StoreCommandFailedException]] if there is an underlying storage issue or the value
    *         didn't exist.
    */
  protected def rawSet(id: K, v: Serialized): Future[Done]

  /**
    * Create the value at the given id.
    *
    * @return A future that completes when the value has been associated with the Id
    *         or fail with [[StoreCommandFailedException]] if there is an underlying storage issue or the
    *         value already existed.
    */
  protected def rawCreate(id: K, v: Serialized): Future[Done]

  private def createOrUpdate[Id, V](
    id: Id,
    change: Option[V] => Try[V])(implicit
    ir: IdResolver[Id, K, V, Serialized],
    m: Marshaller[V, Serialized],
    um: Unmarshaller[Serialized, V],
    ctx: ExecutionContext = ExecutionContext.global): Future[Option[V]] = async {
    val path = ir.toStorageId(id)
    val old = await(get(id))
    change(old) match {
      case Success(newValue) =>
        val serialized = await(Marshal(newValue).to[Serialized])
        old match {
          case Some(_) =>
            await(rawSet(path, serialized))
            old
          case None =>
            await(rawCreate(path, serialized))
            old
        }
      case Failure(error: StoreCommandFailedException) =>
        throw error
      case Failure(error) =>
        old
    }
  }
}
