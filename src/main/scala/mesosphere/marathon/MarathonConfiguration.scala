package mesosphere.marathon

import com.yammer.dropwizard.config.Configuration
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * @author Tobi Knaup
 */

class MarathonConfiguration extends Configuration {
  @JsonProperty
  val mesosMaster = "zk://localhost:2181/mesos"

  @JsonProperty
  val version = "0.0.1"
}
