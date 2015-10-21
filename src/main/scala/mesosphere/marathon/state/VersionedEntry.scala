package mesosphere.marathon.state

trait VersionedEntry {

  import VersionedEntry.VERSION_SEPARATOR

  /**
    * Create a compound version key from id and timestamp.
    */
  def versionKey(id: String, version: Timestamp): String = id + VERSION_SEPARATOR + version.toString

  /**
    * True if the given key is a compound versioned key, otherwise false.
    */
  def isVersionKey(key: String): Boolean = key.indexOf(VERSION_SEPARATOR) > 0

  /**
    * True if the given key is no compound versioned key, otherwise false.
    */
  def noVersionKey(key: String): Boolean = !isVersionKey(key)

  /**
    * Create the key without timestamp part.
    */
  def versionKeyPrefix(name: String): String = name + VERSION_SEPARATOR

  /**
    * Gives the id part of the compound version key.
    */
  def idFromVersionKey(key: String): String = key.substring(0, key.indexOf(VERSION_SEPARATOR))

}

object VersionedEntry {

  /**
    * Separator to separate key and version.
    */
  val VERSION_SEPARATOR = ":"
}
