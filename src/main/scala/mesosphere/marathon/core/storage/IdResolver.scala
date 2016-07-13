package mesosphere.marathon.core.storage

import java.time.OffsetDateTime

/**
  * Resolver for Marathon Internal Persistence IDs (by `Key Type`, `Value Type` and `Serialized Type`)
  * For example, Applications may be identified by the string "/apps/abc" and stored internally at
  * "/apps/a/a58ec17d-735d-4c3f-9aa8-d44c764aa31b"
  *
  * This IdResolver must be provided for all storage engines. See [[PersistenceStore]]
  *
  * @tparam Id The marathon type of the ID for the given Value type
  * @tparam K The persisted type of the ID
  * @tparam Category The category that 'V' belongs to.
  * @tparam V The value type being stored
  * @tparam Serialized The serialized type of 'V' for the given [[PersistenceStore]].
  */
trait IdResolver[Id, K, Category, V, Serialized] {
  /**
    * Translate the marathon id into the given persisted format
    */
  def toStorageId(id: Id, version: Option[OffsetDateTime]): K

  /**
    * The Category 'V' belongs to.
    */
  val category: Category

  /**
    * Translate from the persisted format to the marathon id.
    */
  def fromStorageId(key: K): Id

  /** The maximum number of versions for the given object type */
  val maxVersions: Int

  /**
    * The version of 'V'
    */
  def version(v: V): OffsetDateTime
}