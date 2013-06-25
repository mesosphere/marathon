package mesosphere.marathon

import com.yammer.metrics.core.HealthCheck
import com.yammer.metrics.core.HealthCheck.Result

/**
 * @author Tobi Knaup
 */

class MarathonHealthCheck extends HealthCheck("marathon-health") {
  // TODO implement
  def check(): Result = {
    Result.healthy()
  }
}
