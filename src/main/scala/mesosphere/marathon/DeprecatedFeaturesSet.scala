package mesosphere.marathon

import com.typesafe.scalalogging.StrictLogging

/**
  * Encloses a list of explicitly-enabled deprecated features
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
  def isEnabled(df: DeprecatedFeature) =
    enabledDeprecatedFeatures.contains(df) || (currentVersion < df.softRemoveVersion)

  private def softRemovedFeatures =
    enabledDeprecatedFeatures.filter { df => currentVersion >= df.softRemoveVersion && currentVersion < df.hardRemoveVersion }

  private def hardRemovedFeatures =
    enabledDeprecatedFeatures.filter { df => currentVersion >= df.hardRemoveVersion }

  /**
    * Log appropriate warnings for soft-removed features, errors for hard-removed.
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
    * @return Boolean true if all specified deprecatedFeatures are still allowed in the current version of Marathon
    */
  def isValid(): Boolean = hardRemovedFeatures.isEmpty
}
