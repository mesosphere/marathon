package mesosphere.marathon.core.storage

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}

import scala.concurrent.Future

case class VersionedId[Id](id: Id, version: OffsetDateTime)
case class VersionedValue[Id, V](id: Id, value: V, version: OffsetDateTime)

/**
  * Generic Persistence Store with flexible storage backends using Akka Marshalling Infrastructure.
  *
  * == Providing Serialization for a given class ==
  *  - Provide an [[IdResolver]] for your class for the supported [[PersistenceStore]]s
  *  - Provide a Marshaller and Unmarshaller for
  *    your class and the Serialized form for the supported [[PersistenceStore]]s.
  *  - For example, given a class 'A', a K set to [[mesosphere.marathon.core.storage.impl.zk.ZkId]]
  *    and Serialized as [[mesosphere.marathon.core.storage.impl.zk.ZkSerialized]],
  *    the following implicits should be sufficient.
  *  - While the implicits can be in the companion object, they may be best suited in a trait mixed
  *    into the according Repository.
  * {{{
  *   case class A(id: Int, name: String)
  *   object A {
  *     implicit val zkIdResolver = new IdResolver[Int, ZkId, A, ZkSerialized] {
  *       def toStorageId(id: Int, version: Option[OffsetDateTime]): ZkId =
  *         // note: scaladoc bug where string interpolation fails
  *         ZkId("/A/" + id.toString + "$" + version.map(_.toString).getOrElse(""))
  *       def fromStorageId(key: ZkId): Int = key.value.replaceAll("(^/A/)|(\\$.*$)", "").replaceAll(".toInt
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
  *  - A Large amount of the infrastructure is already provided in the [[BasePersistenceStore]] trait, especially
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
  /**
    * Get a list of all of the Ids of the given Value Types
    */
  def ids[Id, V]()(implicit ir: IdResolver[Id, K, V, Serialized]): Source[Id, NotUsed]

  /**
    * Get a list of all versions for a given id.
    */
  def versions[Id, V](id: Id)(implicit ir: IdResolver[Id, K, V, Serialized]): Source[VersionedId[Id], NotUsed]

  /**
    * Get the current version of the data, if any, for the given primary id and value type.
    *
    * @return A future representing the data at the given Id, if any exists.
    *         If there is an underlying storage problem, the future should fail with [[StoreCommandFailedException]]
    */
  def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, K, V, Serialized],
    um: Unmarshaller[Serialized, V]): Future[Option[V]]

  /**
    * Get the version of the data at the given version, if any, for the given primary id and value type.
    *
    * @return A future representing the data at the given Id, if any exists.
    *         If there is an underlying storage problem, the future should fail with [[StoreCommandFailedException]]
    */
  def get[Id, V](id: Id, version: OffsetDateTime)(implicit
                                                         ir: IdResolver[Id, K, V, Serialized],
                                                  um: Unmarshaller[Serialized, V]): Future[Option[V]]

  /**
    * Store the new value at the given Id. If the value already exists, the existing value will be versioned
    *
    * @return A Future that will complete with the previous version of the value if it existed, or fail with
    *         [[StoreCommandFailedException]] if either a value already exists at the given Id, or
    *         if there is an underlying storage problem
    */
  def store[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, K, V, Serialized],
    m: Marshaller[V, Serialized]): Future[Done]

  /**
    * Store a new value at the given version. If the maximum number of versions has been reached,
    * will delete the oldest versions. This method does not replace the current version.
    *
    * @return A Future that will complete with the previous version of the value if it existed, or fail with
    *         [[StoreCommandFailedException]] if either a value already exists at the given Id, or
    *         if there is an underlying storage problem
    */
  def store[Id, V](id: Id, v: V, version: OffsetDateTime)(
                  implicit ir: IdResolver[Id, K, V, Serialized],
                  m: Marshaller[V, Serialized]): Future[Done]

  /**
    * Delete the value at the given Id and version, idempotent
    *
    * @return A future indicating whether the value was deleted (or simply didn't exist). Underlying storage issues
    *         will fail the future with [[StoreCommandFailedException]]
    */
  def delete[Id, V](k: Id, version: OffsetDateTime)(implicit ir: IdResolver[Id, K, V, Serialized]): Future[Done]

  /**
    * Delete all of the versions of the given Id, idempotent
    * @return A future indicating whether the value was deleted (or simply didn't exist). Underlying storage issues
    *         will fail the future with [[StoreCommandFailedException]]
    */
  def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, K, V, Serialized]): Future[Done]

  /**
    * @return A source of _all_ keys in the Persistence Store (which can be used by a
    *         [[mesosphere.marathon.core.storage.impl.LoadTimeCachingPersistenceStore]] to populate the
    *         cache completely on startup.
    */
  protected[storage] def keys(): Source[K, NotUsed]
}
