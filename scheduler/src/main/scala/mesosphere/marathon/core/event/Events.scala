package mesosphere.marathon
package core.event

import com.fasterxml.jackson.annotation.JsonIgnore
import play.api.libs.json.Json

trait MarathonEvent {
  val eventType: String
  val timestamp: String

  @JsonIgnore
  lazy val jsonString: String = Json.stringify(eventToJson(this))
}
