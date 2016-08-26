package mesosphere.marathon.core.pod

/**
  * Represents a artifact that will be fetched by the Mesos Fetcher.
  * @param uri the URI to fetch
  * @param extract If true, the fetched file shall be extracted.
  * @param executable If true, the fetched file shall be executable.
  * @param cache If true, the fetched file shall be cached.
  * @param destinationPath The destination path where the file shall be available.
  */
case class Artifact(uri: String, extract: Boolean, executable: Boolean, cache: Boolean, destinationPath: Option[String])

object Artifact {
  // TODO (pods): are the defaults correct?
  def apply(uri: String): Artifact = {
    Artifact(uri, extract = false, executable = false, cache = false, destinationPath = None)
  }
}