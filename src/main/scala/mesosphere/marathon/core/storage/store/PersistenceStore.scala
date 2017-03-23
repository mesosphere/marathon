package mesosphere.marathon
package core.storage.store

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.{ Sink, Source }
import akka.{ Done, NotUsed }
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.backup.BackupItem

import scala.concurrent.Future

/**
  * Generic Persistence Store with flexible storage backends using Akka Marshalling Infrastructure.
  *
  * == Providing Serialization for a given class ==
  *  - Provide an [[IdResolver]] for your class for the supported [[PersistenceStore]]s
  *  - Provide a Marshaller and Unmarshaller for
  *    your class and the Serialized form for the supported [[PersistenceStore]]s.
  *  - For example, given a class 'A', a K set to [[mesosphere.marathon.core.storage.store.impl.zk.ZkId]]
  *    and Serialized as [[mesosphere.marathon.core.storage.store.impl.zk.ZkSerialized]],
  *    the following implicits should be sufficient.
  *  - While the implicits can be in the companion object, they may be best suited in a trait mixed
  *    into the according Repository.
  *  - Extend [[mesosphere.marathon.core.storage.repository.impl.PersistenceStoreRepository]] or
  *    [[mesosphere.marathon.core.storage.repository.impl.PersistenceStoreVersionedRepository]] and mix
  *    in the implicits needed.
  * {{{
  *   case class A(id: Int, name: String, version: OffsetDateTime)
  *   object A {
  *     implicit val zkIdResolver = new IdResolver[Int, A, String, ZkId] {
  *       def toStorageId(id: Int, version: Option[OffsetDateTime]): ZkId =
  *         // note: scaladoc bug where string interpolation fails
  *         ZkId(category, id.toString, version)
  *       def fromStorageId(key: ZkId): Int = key.id
  *       val category = "A"
  *       val maxVersions = 2
  *       def version(a: A): OffsetDateTime = a.version
  *     }
  *     implicit val zkMarshaller = Marshaller[A, ZkSerialized] =
  *       Marshaller.opaque { (a: A) =>
  *         val bytes = ByteString.newBuilder
  *         bytes.putInt(a.id)
  *         val strBytes = a.name.getBytes(StandardCharsets.UTF_8)
  *         bytes.putInt(strBytes.length)
  *         bytes.putBytes(strBytes)
  *         bytes.putLong(a.version.toInstant.toEpochMilli)
  *         bytes.putInt(a.version.getOffset.getTotalSeconds)
  *         ZkSerialized(bytes.result)
  *       }
  *     implicit val zkUnmarshaller = Unmarshaller.strict { (zk: ZkSerialized) =>
  *       val it = zk.bytes.iterator
  *       val id = it.getInt
  *       val strLen = it.getInt
  *       val str = new String(it.getBytes(strlen, StandardCharsets.UTF_8))
  *       val time = it.getLong
  *       val offset = it.getInt
  *       val version = OffsetDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneOffset.ofTotalSeconds(offset))
  *       A(id, str, version)
  *     }
  *   }
  * }}}
  *
  * == Notes for implementing new subclasses ==
  *  - A Large amount of the infrastructure is already provided in the
  *    [[mesosphere.marathon.core.storage.store.impl.BasePersistenceStore]] trait, especially
  *    marshalling and unmarshalling and all of the versioning logic.
  *  - Disambiguate the Key and Serialization types when possible,
  *     - e.g. ZkId(String) instead of String, unless they are truly generic,
  *     - e.g. com.google.protobuf.Message can generally be used almost anywhere
  *     that can serialize and deserialize bytes.
  *  - Wrap underlying storage errors in [[mesosphere.marathon.StoreCommandFailedException]],
  *    but leave other exceptions as is.
  *  - Use [[mesosphere.marathon.util.Retry]] - storage layers may have network connectivity issues.
  *  - Ensure your unit test uses the test cases in PersistenceStoreTest and passes all of them.
  *    You may also want to add additional test cases for connectivity.
  *  - Add the type conversions for serialized types, either to their companion object
  *    or within the impl package for your storage layer as appropriate.
  *
  * @tparam K The persistence store's primary key type.
  * @tparam Category The persistence store's category type.
  * @tparam Serialized The serialized format for the persistence store.
  */
trait PersistenceStore[K, Category, Serialized] {
  /**
    * Get a list of all of the Ids of the given Value Types
    */
  def ids[Id, V]()(implicit ir: IdResolver[Id, V, Category, K]): Source[Id, NotUsed]

  /**
    * Get a list of all versions for a given id.
    */
  def versions[Id, V](id: Id)(implicit ir: IdResolver[Id, V, Category, K]): Source[OffsetDateTime, NotUsed]

  /** Get the current version of the storage */
  def storageVersion(): Future[Option[StorageVersion]]

  /** Update the version of the storage */
  def setStorageVersion(storageVersion: StorageVersion): Future[Done]

  /**
    * Get the current version of the data, if any, for the given primary id and value type.
    *
    * @return A future representing the data at the given Id, if any exists.
    *         If there is an underlying storage problem, the future should fail with
    *         [[mesosphere.marathon.StoreCommandFailedException]]
    */
  def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]]

  /**
    * Get the version of the data at the given version, if any, for the given primary id and value type.
    *
    * @return A future representing the data at the given Id, if any exists.
    *         If there is an underlying storage problem, the future should fail with
    *         [[mesosphere.marathon.StoreCommandFailedException]]
    */
  def get[Id, V](
    id: Id,
    version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]]

  /**
    * Get the version of the data at the given id and version, if any, for the value type.
    *
    * @return A future representing the data at the given Id and version, if any exists.
    *         If there is an underlying storage problem, the future should fail with
    *         [[mesosphere.marathon.StoreCommandFailedException]]
    */
  def getVersions[Id, V](list: Seq[(Id, OffsetDateTime)])(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Source[V, NotUsed]

  /**
    * Store the new value at the given Id. If the value already exists, the existing value will be versioned
    *
    * @return A Future that will complete with the previous version of the value if it existed, or fail with
    *         [[mesosphere.marathon.StoreCommandFailedException]] if either a value already exists at the given Id, or
    *         if there is an underlying storage problem
    */
  def store[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done]

  /**
    * Store a new value at the given version. If the maximum number of versions has been reached,
    * will delete the oldest versions. If there is no current version, the value will become the current
    * version, otherwise, will not replace the current version even if this version is newer.
    *
    * @todo Does the above actually make sense? Should we allow an object to have versions without
    *       actually having a current version?
    *
    * @return A Future that will complete with the previous version of the value if it existed, or fail with
    *         [[mesosphere.marathon.StoreCommandFailedException]] if either a value already exists at the given Id, or
    *         if there is an underlying storage problem
    */
  def store[Id, V](id: Id, v: V, version: OffsetDateTime)(
    implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done]

  /**
    * Delete the value at the given id. Does not remove historical versions.
    */
  def deleteCurrent[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done]

  /**
    * Delete the value at the given Id and version, idempotent
    *
    * @return A future indicating whether the value was deleted (or simply didn't exist). Underlying storage issues
    *         will fail the future with [[mesosphere.marathon.StoreCommandFailedException]]
    */
  def deleteVersion[Id, V](k: Id, version: OffsetDateTime)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done]

  /**
    * Delete all of the versions of the given Id, idempotent
    * @return A future indicating whether the value was deleted (or simply didn't exist). Underlying storage issues
    *         will fail the future with [[mesosphere.marathon.StoreCommandFailedException]]
    */
  def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done]

  /**
    * Get a source of all items in the persistence store for a backup.
    * A restore operation with all backup items has to yield the same state that exists in the store at time of calling backup.
    * The BackupItem created by one storage implementation can only be read by the same storage implementation.
    *
    * @return List of all items in the store for backup.
    */
  def backup(): Source[BackupItem, NotUsed]

  /**
    * Restore a state from a previously created backup.
    * The current state of the persistent store has to be cleaned before backup items can be restored!
    * The sink needs to write all items for a complete backup.
    * @return a sink that can be used to restore the complete state.
    */
  def restore(): Sink[BackupItem, Future[Done]]
}
