package mesosphere.marathon.state

import org.apache.mesos.{ Protos => mesos }
import scala.collection.immutable.Seq

/**
  * Defaults taken from mesos.proto
  */
case class FetchUri(
    uri: String,
    extract: Boolean = true,
    executable: Boolean = false,
    cache: Boolean = false) {

  def toProto(): mesos.CommandInfo.URI =
    mesos.CommandInfo.URI.newBuilder()
      .setValue(uri)
      .setExecutable(executable)
      .setExtract(extract)
      .setCache(cache)
      .build()
}

object FetchUri {

  val empty: Seq[FetchUri] = Seq.empty

  def fromProto(uri: mesos.CommandInfo.URI): FetchUri =
    FetchUri(
      uri = uri.getValue,
      executable = uri.getExecutable,
      extract = uri.getExtract,
      cache = uri.getCache
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
