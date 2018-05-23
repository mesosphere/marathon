package mesosphere.marathon

import com.typesafe.scalalogging.StrictLogging

object DeprecatedFeatures extends StrictLogging {
  case class DeprecatedFeature(
      key: String,
      description: String,
      warnVersion: String,
      removeVersion: String
  )

  val syncProxy = DeprecatedFeature(
    "sync_proxy",
    description = "Old, blocking IO implementation for leader proxy used by Marathon standby instances.",
    warnVersion = "1.7.0",
    removeVersion = "1.8.0")

  val all = Seq(syncProxy)

  private val versionOrdering: Ordering[Iterable[Int]] = implicitly[Ordering[Iterable[Int]]]

  private def simpleVersionParse(ver: String): List[Int] =
    ver.split("[^0-9]+").take(3).map(_.toInt)(collection.breakOut)

  /**
    * Provided a list of enabled deprecated features, output appropriate log messages based on current version and
    * deprecation / removal versions.
    *
    * If a removed feature is included, log an error and exit abruptly.
    */
  def warnOrFail(deprecatedFeatures: Iterable[DeprecatedFeature]): Unit = {
    var failed = false
    val currentVersion = simpleVersionParse(BuildInfo.version)

    deprecatedFeatures.foreach { df =>
      if (versionOrdering.gteq(currentVersion, simpleVersionParse(df.removeVersion))) {
        failed = true
        logger.error(s"${df.key} has been removed in ${df.removeVersion}. You should migrate back to a previous " +
          "version of Marathon, remove the deprecated feature flag, and ensure that your cluster continues to work.")
      } else if (versionOrdering.gteq(currentVersion, simpleVersionParse(df.warnVersion))) {
        logger.warn(s"${df.key} will be removed in ${df.removeVersion}. You should remove the deprecated feature " +
          "flag as soon possible.")
      }
    }
    if (failed)
      System.exit(1)
  }
}
