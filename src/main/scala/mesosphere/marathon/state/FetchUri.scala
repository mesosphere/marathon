package mesosphere.marathon
package state

import org.apache.mesos.{ Protos => mesos }
import scala.collection.immutable.Seq

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
    outputFile.foreach{ name => builder.setOutputFile(name) }
    builder.build()
  }
}

object FetchUri {

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

  def isExtract(uri: String): Boolean = {
    uri.endsWith(".tgz") ||
      uri.endsWith(".tar.gz") ||
      uri.endsWith(".tbz2") ||
      uri.endsWith(".tar.bz2") ||
      uri.endsWith(".txz") ||
      uri.endsWith(".tar.xz") ||
      uri.endsWith(".zip")
  }
}
