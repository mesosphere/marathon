package mesosphere.marathon.state

import org.apache.mesos.{ Protos => mesos }
import scala.collection.immutable.Seq

/**
  * Defaults taken from mesos.proto
  */
case class FetchUri(
    uri: String,
    executable: Boolean = false,
    extract: Boolean = true,
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
}
