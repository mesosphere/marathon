package mesosphere.marathon
package core.plugin

import play.api.libs.json.JsObject

case class PluginDefinition(
    id: String,
    plugin: String,
    implementation: String,
    tags: Option[Set[String]],
    configuration: Option[JsObject],
    info: Option[JsObject],
    enabled: Option[Boolean])

case class PluginDefinitions(plugins: Seq[PluginDefinition]) {
  def hasSecretPlugin: Boolean =
    plugins.exists { pd =>
      pd.tags.exists(_.contains(PluginDefinitions.SECRET_TAG))
    }
}

object PluginDefinitions {
  lazy val None = PluginDefinitions(Seq.empty[PluginDefinition])
  val SECRET_TAG = "secret"
}
