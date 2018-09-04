package mesosphere.marathon

case class DeprecatedFeature(
    key: String,
    description: String,
    softRemoveVersion: SemVer,
    hardRemoveVersion: SemVer) {
  require(hardRemoveVersion > softRemoveVersion)
}

object DeprecatedFeatures {
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

  def all = Seq(syncProxy, jsonSchemasResource, apiHeavyEvents, proxyEvents, kamonMetrics)

  def description: String = {
    "  - " + all.map { df =>
      s"${df.key}: ${df.description} (soft-remove: ${df.softRemoveVersion}; hard-remove: ${df.hardRemoveVersion})"
    }.mkString("\n  - ")
  }
}
