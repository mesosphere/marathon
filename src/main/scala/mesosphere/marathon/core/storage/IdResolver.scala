package mesosphere.marathon.core.storage

import java.time.OffsetDateTime

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
  *       def toStorageId(id: String, version: Option[OffsetDateTime]): String =
  *         version.fold(id)(ts => id.${ts.toString})
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
  def toStorageId(id: Id, version: Option[OffsetDateTime]): K

  def toStorageCategory: K

  /**
    * Translate from the persisted format to the marathon id.
    */
  def fromStorageId(key: K): Id

  /** The maximum number of versions for the given object type */
  val maxVersions: Int
}