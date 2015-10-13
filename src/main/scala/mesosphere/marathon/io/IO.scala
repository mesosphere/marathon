package mesosphere.marathon.io

import java.io._
import java.math.BigInteger
import java.nio.file.{ Files, Path, Paths }
import java.security.{ DigestInputStream, MessageDigest }
import java.util.zip.{ GZIPInputStream, GZIPOutputStream }

import com.google.common.io.ByteStreams

import scala.annotation.tailrec
import scala.util.{ Failure, Success, Try }

object IO {

  private val BufferSize = 8192

  def readFile(file: String): Array[Byte] = readFile(Paths.get(file))
  def readFile(path: Path): Array[Byte] = Files.readAllBytes(path)

  def listFiles(file: String): Array[File] = listFiles(new File(file))
  def listFiles(file: File): Array[File] = {
    if (!file.exists()) throw new FileNotFoundException(file.getAbsolutePath)
    if (!file.isDirectory) throw new FileNotFoundException(s"File ${file.getAbsolutePath} is not a directory!")
    file.listFiles()
  }

  def moveFile(from: File, to: File): File = {
    if (to.exists()) delete(to)
    createDirectory(to.getParentFile)
    if (!from.renameTo(to)) {
      copyFile(from, to)
      delete(from)
    }
    to
  }

  def copyFile(sourceFile: File, targetFile: File) {
    require(sourceFile.exists, "Source file '" + sourceFile.getAbsolutePath + "' does not exist.")
    require(!sourceFile.isDirectory, "Source file '" + sourceFile.getAbsolutePath + "' is a directory.")
    using(new FileInputStream(sourceFile)) { source =>
      using(new FileOutputStream(targetFile)) { target =>
        transfer(source, target, close = false)
      }
    }
  }

  def createDirectory(dir: File) {
    if (!dir.exists()) {
      val result = dir.mkdirs()
      if (!result || !dir.isDirectory || !dir.exists)
        throw new IOException("Can not create Directory: " + dir.getAbsolutePath)
    }
  }

  def delete(file: File) {
    if (file.isDirectory) {
      file.listFiles().foreach(delete)
    }
    file.delete()
  }

  def mdSum(
    in: InputStream,
    mdName: String = "SHA-1",
    out: OutputStream = ByteStreams.nullOutputStream()): String = {
    val md = MessageDigest.getInstance(mdName)
    transfer(new DigestInputStream(in, md), out)
    //scalastyle:off magic.number
    new BigInteger(1, md.digest()).toString(16)
    //scalastyle:on
  }

  def gzipCompress(bytes: Array[Byte]): Array[Byte] = {
    val out = new ByteArrayOutputStream(bytes.length)
    using(new GZIPOutputStream(out)) { gzip =>
      gzip.write(bytes.toArray)
      gzip.flush()
    }
    out.toByteArray
  }

  def gzipUncompress(bytes: Array[Byte]): Array[Byte] = {
    using(new GZIPInputStream(new ByteArrayInputStream(bytes))) { in =>
      ByteStreams.toByteArray(in)
    }
  }

  def transfer(
    in: InputStream,
    out: OutputStream,
    close: Boolean = true,
    continue: => Boolean = true) {
    try {
      val buffer = new Array[Byte](BufferSize)
      @tailrec def read() {
        val byteCount = in.read(buffer)
        if (byteCount >= 0 && continue) {
          out.write(buffer, 0, byteCount)
          out.flush()
          read()
        }
      }
      read()
    }
    finally { if (close) Try(in.close()) }
  }

  def copyInputStreamToString(in: InputStream): String = {
    val out = new ByteArrayOutputStream()
    transfer(in, out)
    new String(out.toByteArray, "UTF-8")
  }

  def withResource[T](path: String)(fn: InputStream => T): Option[T] = {
    Option(getClass.getResourceAsStream(path)).flatMap { stream =>
      Try(stream.available()) match {
        case Success(length) => Some(fn(stream))
        case Failure(ex)     => None
      }
    }
  }

  def using[A <: Closeable, B](closeable: A)(fn: (A) => B): B = {
    try {
      fn(closeable)
    }
    finally {
      Try(closeable.close())
    }
  }
}

