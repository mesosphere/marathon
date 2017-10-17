package mesosphere.marathon
package upgrade

import mesosphere.marathon.state.{ AppDefinition, RootGroup, Timestamp, VersionInfo }
import org.slf4j.LoggerFactory

/**
  * Tools related to app/group versioning.
  */
object GroupVersioningUtil {
  private[this] val log = LoggerFactory.getLogger(getClass)

  /**
    * Calculate a new group from the given `to` parameter that sets the version of all changed apps
    * to the given `version`.
    *
    * The `RootGroup` object returned from this function, has its version set to the given `version`.
    * Therefore even if there are no changed apps, the version of `RootGroup` object returned is always
    * set to `version`.
    *
    * @param version the version of all changed apps
    * @param from the original group
    * @param to the updated group
    * @return the updated group with updated app versions
    */
  def updateVersionInfoForChangedApps(version: Timestamp, from: RootGroup, to: RootGroup): RootGroup = {

    def updateAppVersionInfo(maybeOldApp: Option[AppDefinition], newApp: AppDefinition): AppDefinition = {
      val newVersionInfo = maybeOldApp match {
        case None =>
          log.info(s"[${newApp.id}]: new app detected")
          VersionInfo.forNewConfig(newVersion = version)
        case Some(oldApp) =>
          if (oldApp.isUpgrade(newApp)) {
            log.info(s"[${newApp.id}]: upgrade detected for app (oldVersion ${oldApp.versionInfo})")
            oldApp.versionInfo.withConfigChange(newVersion = version)
          } else if (oldApp.isOnlyScaleChange(newApp)) {
            log.info(s"[${newApp.id}]: scaling op detected for app (oldVersion ${oldApp.versionInfo})")
            oldApp.versionInfo.withScaleOrRestartChange(newVersion = version)
          } else if (oldApp.versionInfo != newApp.versionInfo && newApp.versionInfo == VersionInfo.NoVersion) {
            log.info(s"[${newApp.id}]: restart detected for app (oldVersion ${oldApp.versionInfo})")
            oldApp.versionInfo.withScaleOrRestartChange(newVersion = version)
          } else {
            oldApp.versionInfo
          }
      }

      newApp.copy(versionInfo = newVersionInfo)
    }

    val originalApps = from.transitiveAppsById
    val updatedTargetApps = to.transitiveApps.flatMap { newApp =>
      val updated = updateAppVersionInfo(originalApps.get(newApp.id), newApp)
      if (updated.versionInfo != newApp.versionInfo) Some(updated) else None
    }
    val updatedTo = to.updateVersion(version)
    updatedTargetApps.foldLeft(updatedTo) { (resultGroup, updatedApp) =>
      resultGroup.updateApp(updatedApp.id, _ => updatedApp, version)
    }
  }

}
