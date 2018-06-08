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

  def all = Seq(syncProxy, jsonSchemasResource)
}
