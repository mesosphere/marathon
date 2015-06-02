package mesosphere.marathon.io.storage

import java.io.{ InputStream, OutputStream }
import java.net.URI

import mesosphere.marathon.io.IO
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{ FileSystem, Path }

/**
  * The Hadoop File System implementation.
  *
  * @param fs the related hadoop file system.
  * @param fsPath the path inside the file system.
  */
case class HDFSStorageItem(fs: FileSystem, fsPath: Path, base: String, path: String) extends StorageItem {

  def store(fn: (OutputStream) => Unit): StorageItem = {
    IO.using(fs.create(fsPath, true)) { fn }
    this
  }

  def moveTo(newPath: String): StorageItem = {
    val toPath = new Path(base, newPath)
    if (fs.exists(toPath)) fs.delete(toPath, false)
    val result = fs.rename(fsPath, toPath)
    assert(result, s"HDFS file system could not move $fsPath to $newPath")
    HDFSStorageItem(fs, toPath, base, newPath)
  }

  def url: String = fs.getUri.toString + fsPath.toUri.toString
  def inputStream(): InputStream = fs.open(fsPath)
  def length: Long = fs.getFileStatus(fsPath).getLen
  def lastModified: Long = fs.getFileStatus(fsPath).getModificationTime
  def delete(): Unit = fs.delete(fsPath, true)
  def exists: Boolean = fs.exists(fsPath)
}

/**
  * The Hadoop File System Storage provider.
  *
  * @param uri the uri of the hadoop dfs.
  * @param configuration the configuration used to connect.
  */
class HDFSStorageProvider(uri: URI, base: String, configuration: Configuration) extends StorageProvider {
  val fs = FileSystem.get(uri, configuration)

  override def item(path: String): StorageItem = {
    HDFSStorageItem(fs, new Path(base, path), base, path)
  }
}

