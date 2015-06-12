package mesosphere.marathon.api.v2.json

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
case class V2AppDefinitionWrapper(app: V2AppDefinition)
