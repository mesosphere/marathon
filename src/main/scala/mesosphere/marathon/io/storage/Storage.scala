package mesosphere.marathon.io.storage

import java.io.{ File, FileInputStream, InputStream, OutputStream }
import java.net.URI

import org.apache.hadoop.conf.Configuration

import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.io.IO

/**
  * The storage item is an entry in the storage, which is identified by a path.
  * The path is the unique identifier.
  */
trait StorageItem extends IO {

  /**
    * The provider independent path of the item.
    */
  def path: String

  /**
    * The provider specific url ot this item.
    */
  def url: String

  /**
    * Read the item and give the input stream.
    */
  def inputStream(): InputStream

  /**
    * Move this item inside the storage system.
    * @param path the new path of this item
    * @return the moved storage item.
    */
  def moveTo(path: String): StorageItem

  /**
    * Delete this item.
    */
  def delete(): Unit

  /**
    * Returns the time that the item was last modified.
    */
  def lastModified: Long

  /**
    * Returns the length of the item.
    */
  def length: Long

  /**
    * Indicates, whether the item exists in the file system or not.
    */
  def exists: Boolean

  /**
    * Store this item with given output stream function.
    */
  def store(fn: OutputStream => Unit): StorageItem

  /**
    * Store this item with given file input.
    */
  def store(from: File): StorageItem = {
    using(new FileInputStream(from)) { in =>
      store(out => transfer(in, out))
    }
  }

  /**
    * Store this item with given item input.
    */
  def store(from: StorageItem): StorageItem = {
    using(from.inputStream()) { in =>
      store(out => transfer(in, out))
    }
  }

  /**
    * Store this item with given input stream.
    */
  def store(in: InputStream): StorageItem = {
    store(out => transfer(in, out))
  }
}

/**
  * The storage provider can create specific storage items based on a path.
  */
trait StorageProvider {
  def item(path: String): StorageItem
}

object StorageProvider {
  val HDFS = "^(hdfs://[^/]+)(.*)$".r // hdfs://host:port/path
  val FILE = "^file://(.*)$".r // file:///local/artifact/path

  def provider(config: MarathonConf, http: HttpConf): StorageProvider =
    config.artifactStore.get.getOrElse("") match {
      case HDFS(uri, base) =>
        new HDFSStorageProvider(
          new URI(uri),
          if (base.isEmpty) "/" else base,
          new Configuration()
        )

      case FILE(base) =>
        new FileStorageProvider(
          s"http://${config.hostname.get.get}:${http.httpPort.get.get}/v2/artifacts",
          new File(base)
        )

      case _ =>
        new NoStorageProvider()
    }

  def isValidUrl(url: String): Boolean = url match {
    case HDFS(_, _) => true
    case FILE(_)    => true
    case _          => false
  }

  def examples: Map[String, String] = Map (
    "hdfs" -> "hdfs://localhost:54310/path/to/store",
    "file" -> "file:///var/log/store"
  )
}

