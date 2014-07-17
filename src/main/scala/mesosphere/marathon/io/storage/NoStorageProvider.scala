package mesosphere.marathon.io.storage

import java.io._

/**
  * Dummy no-op storage item.
  */
case class NoStorageItem(path: String) extends StorageItem {
  def url: String = ""
  def inputStream(): Nothing = throw new FileNotFoundException(path)
  def store(fn: (OutputStream) => Unit) = this
  def delete() {}
  def lastModified: Long = 0L
  def length: Long = 0L
  def exists: Boolean = false
  def toFile: Nothing = throw new FileNotFoundException(path)
  def moveTo(to: String): NoStorageItem = NoStorageItem(to)
}

/**
  * Dummy no-op provider.
  */
class NoStorageProvider extends StorageProvider {
  def item(path: String): NoStorageItem = NoStorageItem(path)
  def assetURLBase: String = ""
}
