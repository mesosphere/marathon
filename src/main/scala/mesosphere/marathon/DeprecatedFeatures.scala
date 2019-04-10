package mesosphere.marathon

case class DeprecatedFeature(
    key: String,
    description: String,
    softRemoveVersion: SemVer,
    hardRemoveVersion: SemVer) {
  require(hardRemoveVersion > softRemoveVersion)
}

/**
  * Contains list of available and removed DeprecatedFeatures.
  *
  * We do not want to delete a hard-removed deprecated feature until one full minor version after the hard removal
  * date. For example, if a deprecated feature is hard-removed in 1.8.0, then:
  *
  *   - 1.8.0: The associated code for the deprecated feature is removed
  *   - 1.9.0: The deprecated flag is removed.
  *
  * This way, if they upgrade to 1.8.0, we can show the operator a nice error message in the logs telling them that the
  * specific deprecated feature they tried to enable is permanently removed, with a recommendation for how to proceed,
  * as opposed to simply telling them it is an unknown deprecated feature.
  */
object DeprecatedFeatures {

  val appC = DeprecatedFeature(
    "app_c",
    description = "appC is not supported in Marathon, Please use either Docker or UCR (Mesos) containerizers",
    softRemoveVersion = SemVer(1, 8, 0),
    hardRemoveVersion = SemVer(1, 9, 0))

  /* Removed */
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

  val apiHeavyEvents = DeprecatedFeature(
    "api_heavy_events",
    description = "Enables the legacy heavy events format returned via /v2/events which makes /v2/events unusable in larger cluster. Light events are returned with /v2/events?plan-format=light and include a subset of the fields.",
    softRemoveVersion = SemVer(1, 7, 0),
    hardRemoveVersion = SemVer(1, 8, 0))

  val proxyEvents = DeprecatedFeature(
    "proxy_events",
    description = "Proxy /v2/events when requested from a non-leader",
    softRemoveVersion = SemVer(1, 7, 0),
    hardRemoveVersion = SemVer(1, 8, 0))

  val kamonMetrics = DeprecatedFeature(
    "kamon_metrics",
    description = "Enables the legacy metrics implemented using Kamon library.",
    softRemoveVersion = SemVer(1, 7, 0),
    hardRemoveVersion = SemVer(1, 8, 0))

  def all = Seq(syncProxy, jsonSchemasResource, apiHeavyEvents, proxyEvents, kamonMetrics, appC)

  def description: String = {
    "  - " + all.map { df =>
      s"${df.key}: ${df.description} (soft-remove: ${df.softRemoveVersion}; hard-remove: ${df.hardRemoveVersion})"
    }.mkString("\n  - ")
  }
}
