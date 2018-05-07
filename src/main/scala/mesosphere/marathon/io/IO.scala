package mesosphere.marathon
package io

import java.io.{BufferedInputStream, Closeable, File, FileInputStream, FileOutputStream, FileNotFoundException, InputStream, OutputStream}

import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.IOUtils
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object IO extends StrictLogging {

  /**
    * This method follows symlinks by invoking `getCanonicalFile` method on the File object. A canonical pathname is
    * both absolute and unique. The precise definition of canonical form is system-dependent. `getCanonicalFile` first
    * converts this pathname to absolute form if necessary, as if by invoking the getAbsolutePath() method, and then
    * maps it to its unique form in a system-dependent way. This typically involves removing redundant names such as "."
    * and ".." from the pathname, resolving symbolic links (on UNIX platforms), and converting drive letters to a
    * standard case (on Microsoft Windows platforms).
    *
    * @param file string path to the directory
    * @return an array of file objects in the directory
    */
  def listFiles(file: String): Array[File] = listFiles(new File(file).getCanonicalFile)

  def listFiles(file: File): Array[File] = {
    if (!file.exists()) throw new FileNotFoundException(file.getAbsolutePath)
    if (!file.isDirectory) throw new FileNotFoundException(s"File ${file.getAbsolutePath} is not a directory!")
    file.listFiles()
  }

  def withResource[T](path: String)(fn: InputStream => T): Option[T] = {
    Option(getClass.getResourceAsStream(path)).flatMap { stream =>
      Try(stream.available()) match {
        case Success(length) => Some(fn(stream))
        case Failure(ex) => None
      }
    }
  }

  def using[A <: Closeable, B](closeable: A)(fn: (A) => B): B = {
    try {
      fn(closeable)
    } finally {
      IOUtils.closeQuietly(closeable)
    }
  }

  /**
    * Copies all bytes from an input stream to an outputstream.
    *
    * The method is adapted from [[com.google.common.io.ByteStreams.copy]] with the only difference that we flush after
    * each write. Note: This method is blocking!
    *
    * @param maybeFrom Inputstream for copy from.
    * @param maybeTo Outputstream to copy to.
    * @return
    */
  def transfer(maybeFrom: Option[InputStream], maybeTo: Option[OutputStream]): Long = {
    (maybeFrom, maybeTo) match {
      case (Some(from), Some(to)) =>
        @tailrec def iter(buf: Array[Byte], total: Long): Long =
          from.read(buf) match {
            case -1 => total
            case r =>
              to.write(buf, 0, r)
              to.flush()
              iter(buf, total + r)
          }

        iter(new Array[Byte](8192), 0L)
      case _ =>
        logger.debug("Did not copy any data.")
        0
    }
  }

  def transfer(from: InputStream, to: OutputStream): Long = transfer(Some(from), Some(to))

  /**
    * Extracts a tarball GZipped file to and output directory.
    *
    * @param tgzFile The tarball file to extract.
    * @param outDir The target output directory.
    */
  def extractTGZip(tgzFile: File, outDir: File): Unit = {
    logger.debug(s"Extracting ${tgzFile.getCanonicalPath} to ${outDir.getCanonicalPath}")
    val tarIs = new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(tgzFile))))
    var entry = tarIs.getNextTarEntry
    while (entry != null) {
      val destPath = new File(outDir, entry.getName)
      if (entry.isDirectory) destPath.mkdirs()
      else {
        destPath.getParentFile.mkdirs()
        destPath.createNewFile
        transfer(tarIs, new FileOutputStream(destPath))
      }
      entry = tarIs.getNextTarEntry
    }
    tarIs.close()
  }
}

