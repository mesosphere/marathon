package mesosphere.marathon

import com.typesafe.scalalogging.StrictLogging

case class DeprecatedFeature(
    key: String,
    description: String,
    softRemoveVersion: SemVer,
    hardRemoveVersion: SemVer) {
  require(hardRemoveVersion > softRemoveVersion)
}

object DeprecatedFeatures extends StrictLogging {
  val syncProxy = DeprecatedFeature(
    "sync_proxy",
    description = "Old, blocking IO implementation for leader proxy used by Marathon standby instances.",
    softRemoveVersion = SemVer(1, 6, 0),
    hardRemoveVersion = SemVer(1, 8, 0))

  val jsonSchemasResource = DeprecatedFeature(
    "json_schemas_resource",
    description = "Enables the /v2/schemas route. JSON Schema has been deprecated in favor of RAML and many of the definitions are not up-to-date with the current API",
    softRemoveVersion = SemVer(1, 7, 0),
    hardRemoveVersion = SemVer(1, 8, 0))

  def all = Seq(syncProxy, jsonSchemasResource)
}

/**
  * Provided a list of enabled deprecated features, output appropriate log messages based on current version and
  * deprecation / removal versions.
  *
  * @param currentVersion The current version of Marathon
  * @param enbaledDeprecatedFeatures The deprecated features specified to be enable
  */
case class DeprecatedFeatureSet(
    currentVersion: SemVer,
    enabledDeprecatedFeatures: Set[DeprecatedFeature]) extends StrictLogging {

  /**
    * If a deprecated feature has not been soft-removed (still enabled by default), then return true
    *
    * Otherwise, only return true if the feature in question has been explicitly enabled.
    */
  def isEnabled(df: DeprecatedFeature) = {
    enabledDeprecatedFeatures.contains(df) || (currentVersion < df.softRemoveVersion)
  }

  private def softRemovedFeatures =
    enabledDeprecatedFeatures.filter { df => currentVersion >= df.softRemoveVersion && currentVersion < df.hardRemoveVersion }

  private def hardRemovedFeatures =
    enabledDeprecatedFeatures.filter { df => currentVersion >= df.hardRemoveVersion }

  /**
    * Log warnings for soft-removed features, errors for hard-removed.
    *
    */
  def logDeprecationWarningsAndErrors(): Unit = {
    softRemovedFeatures.foreach { df =>
      logger.warn(s"Deprecated feature ${df.key} is scheduled to be removed in ${df.hardRemoveVersion}. You should " +
        "remove the deprecated feature flag as soon possible.")
    }

    hardRemovedFeatures.foreach { df =>
      logger.error(s"${df.key} has been removed as of ${df.hardRemoveVersion}. It has the following description:\n\n" +
        df.description + "\n\n" +
        "You should migrate back to a previous version of Marathon, remove the deprecated feature flag, ensure that " +
        "your cluster continues to work, and then upgrade again.\n\n" +
        "If you are confident that you no longer need the deprecated feature flag, you can simply remove it.")
    }
  }

  /**
    * Returns if all deprecated features are still allowed to be enabled.
    *
    * @return Boolean true if all specified deprecatedFeatures are still allowed in the current version of Marathon
    */
  def isValid(): Boolean = hardRemovedFeatures.isEmpty
}
