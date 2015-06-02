package mesosphere.marathon.io.storage

import java.io._

import mesosphere.marathon.io.IO

/**
  * The local file system implementation.
  *
  * @param file the underlying file
  * @param path the relative path, this item is identified with.
  */
case class FileStorageItem(file: File, basePath: File, path: String, baseUrl: String) extends StorageItem {

  def store(fn: OutputStream => Unit): FileStorageItem = {
    IO.createDirectory(file.getParentFile)
    IO.using(new FileOutputStream(file)) { fn }
    this
  }

  def moveTo(path: String): FileStorageItem = {
    val to = new File(basePath, path)
    IO.moveFile(file, to)
    cleanUpDir(file.getParentFile)
    FileStorageItem(to, basePath, path, url)
  }

  def url: String = s"$baseUrl/$path"
  def inputStream(): InputStream = new FileInputStream(file)
  def lastModified: Long = file.lastModified()
  def length: Long = file.length()
  def exists: Boolean = file.exists()

  def delete() {
    file.delete()
    cleanUpDir(file.getParentFile)
  }

  private def cleanUpDir(dir: File) {
    if (!dir.isFile && dir != basePath && dir.list().isEmpty) {
      dir.delete()
      cleanUpDir(dir.getParentFile)
    }
  }
}

/**
  * The local file system storage implementation.
  *
  * @param basePath the base path to the managed asset directory
  */
class FileStorageProvider(val url: String, val basePath: File) extends StorageProvider {
  require(basePath.exists(), "Base path does not exist: %s. Configuration error?".format(basePath.getAbsolutePath))

  def item(path: String): FileStorageItem = {
    val file: File = new File(basePath, path)
    //make sure, no file from outside base path is created
    if (!file.getCanonicalPath.startsWith(basePath.getCanonicalPath)) throw new IOException("Access Denied")
    new FileStorageItem(file, basePath, path, url)
  }
}
