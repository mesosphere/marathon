package mesosphere.marathon
package io

import java.io.{ Closeable, File, FileNotFoundException, InputStream }

import org.apache.commons.io.IOUtils
import scala.util.{ Failure, Success, Try }

object IO {

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
}

