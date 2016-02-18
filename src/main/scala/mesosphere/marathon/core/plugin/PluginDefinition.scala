package mesosphere.marathon.core.plugin

import play.api.libs.json.JsObject

case class PluginDefinition(id: String,
                            plugin: String,
                            implementation: String,
                            tags: Option[Set[String]],
                            configuration: Option[JsObject],
                            info: Option[JsObject])

case class PluginDefinitions(plugins: Seq[PluginDefinition])
