package mesosphere.marathon.io

import java.io._
import java.math.BigInteger
import java.security.{ MessageDigest, DigestInputStream }
import scala.annotation.tailrec

import com.google.common.io.ByteStreams

trait IO {

  private val BufferSize = 8192

  protected def moveFile(from: File, to: File): File = {
    if (to.exists()) delete(to)
    createDirectory(to.getParentFile)
    if (!from.renameTo(to)) {
      copyFile(from, to)
      delete(from)
    }
    to
  }

  protected def copyFile(sourceFile: File, targetFile: File) {
    require(sourceFile.exists, "Source file '" + sourceFile.getAbsolutePath + "' does not exist.")
    require(!sourceFile.isDirectory, "Source file '" + sourceFile.getAbsolutePath + "' is a directory.")
    using(new FileInputStream(sourceFile)) { source =>
      using(new FileOutputStream(targetFile)) { target =>
        transfer(source, target, close = false)
      }
    }
  }

  protected def createDirectory(dir: File) {
    if (!dir.exists()) {
      val result = dir.mkdirs()
      if (!result || !dir.isDirectory || !dir.exists)
        throw new IOException("Can not create Directory: " + dir.getAbsolutePath)
    }
  }

  protected def delete(file: File) {
    if (file.isDirectory) {
      file.listFiles().foreach(delete)
    }
    file.delete()
  }

  protected def mdSum(
    in: InputStream,
    mdName: String = "SHA-1",
    out: OutputStream = ByteStreams.nullOutputStream()): String = {
    val md = MessageDigest.getInstance(mdName)
    transfer(new DigestInputStream(in, md), out)
    new BigInteger(1, md.digest()).toString(16)
  }

  protected def transfer(
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
          read()
        }
      }
      read()
    }
    finally { if (close) in.close() }
  }

  protected def using[A <: Closeable, B](closeable: A)(fn: (A) => B): B = {
    try { fn(closeable) } finally { closeable.close() }
  }
}

