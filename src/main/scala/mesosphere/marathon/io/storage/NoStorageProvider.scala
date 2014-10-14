package mesosphere.marathon.io.storage

/**
  * Dummy no-op provider.
  */
class NoStorageProvider extends StorageProvider {

  def item(path: String): Nothing =
    throw new IllegalArgumentException(
      "No storage provider available to load/persist artifacts. Configuration problem?"
    )

  def assetURLBase: String = ""

}
