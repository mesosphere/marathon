package mesosphere.marathon
package io

import java.io.{ Closeable, File, FileNotFoundException, InputStream, OutputStream }

import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.IOUtils

import scala.util.{ Failure, Success, Try }
import scala.util.control.Breaks.break

object IO extends StrictLogging {

  def createBuffer() = new Array[Byte](8192)

  def listFiles(file: String): Array[File] = listFiles(new File(file))
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
    * @param mabyeTo Outputstream to copy to.
    * @return
    */
  def transfer(maybeFrom: Option[InputStream], mabyeTo: Option[OutputStream]): Long = {
    (maybeFrom, mabyeTo) match {
      case (Some(from), Some(to)) =>
        val buf = createBuffer();
        var total = 0
        while (true) {
          val r = from.read(buf)
          if (r == -1) {
            break
          }
          to.write(buf, 0, r)
          to.flush()
          total += r
        }
        total
      case _ =>
        logger.debug("Did not copy any data.")
        0
    }
  }
}

