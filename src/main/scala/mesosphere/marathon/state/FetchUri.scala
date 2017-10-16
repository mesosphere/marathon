package mesosphere.marathon
package state

import java.net.URI

import org.apache.mesos.{ Protos => mesos }

import scala.collection.immutable.Seq
import scala.util.Try

/**
  * Defaults taken from mesos.proto
  */
case class FetchUri(
    uri: String,
    extract: Boolean = FetchUri.defaultExtract,
    executable: Boolean = FetchUri.defaultExecutable,
    cache: Boolean = FetchUri.defaultCache,
    outputFile: Option[String] = FetchUri.defaultOutputFile) {

  def toProto: mesos.CommandInfo.URI = {
    val builder = mesos.CommandInfo.URI.newBuilder()
      .setValue(uri)
      .setExecutable(executable)
      .setExtract(extract)
      .setCache(cache)
    outputFile.foreach { name => builder.setOutputFile(name) }
    builder.build()
  }
}

object FetchUri {

  // Official extractable extensions of mesos fetcher: http://mesos.apache.org/documentation/latest/fetcher/
  lazy val supportedFileTypes = Seq(".tar", ".tar.gz", ".tar.bz2", ".tar.xz", ".gz", ".tgz", ".tbz2", ".txz", ".zip")

  val empty: Seq[FetchUri] = Seq.empty

  val defaultExtract: Boolean = raml.Artifact.DefaultExtract
  val defaultExecutable: Boolean = raml.Artifact.DefaultExecutable
  val defaultCache: Boolean = raml.Artifact.DefaultCache
  val defaultOutputFile: Option[String] = raml.Artifact.DefaultDestPath

  def fromProto(uri: mesos.CommandInfo.URI): FetchUri =
    FetchUri(
      uri = uri.getValue,
      executable = uri.getExecutable,
      extract = uri.getExtract,
      cache = uri.getCache,
      outputFile = if (uri.hasOutputFile) Some(uri.getOutputFile) else None
    )

  /**
    * Method that checks if the given string is a valid URI and if the file type in the
    * URI is matching a supported file type.
    *
    * @param uri String that contains a URI.
    * @return true if the extension indicates an extractable file, false otherwise.
    */
  def isExtract(uri: String): Boolean = {
    Try(new URI(uri)).map { u =>
      supportedFileTypes.exists { fileType =>
        u.getPath.endsWith(fileType)
      }
    }.getOrElse(false)
  }
}
