package mesosphere.marathon.state

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

/**
  * Configures exponential backoff behavior when launching potentially sick apps.
  * This prevents sandboxes associated with consecutively failing tasks from filling up the hard disk on Mesos slaves.
  * The backoff period is multiplied by the factor for each consecutive failure until it reaches maxLaunchDelaySeconds.
  * This applies also to instances that are killed due to failing too many health checks.
  * @param backoff The initial backoff applied when a launched instance fails.
  *   minimum: 0.0
  * @param factor The factor applied to the current backoff to determine the new backoff.
  *   minimum: 0.0
  * @param maxLaunchDelay The maximum backoff applied when subsequent failures are detected.
  *   minimum: 0.0
  */
case class BackoffStrategy(backoff: FiniteDuration, maxLaunchDelay: FiniteDuration, factor: Double)

object BackoffStrategy {
  def apply(): BackoffStrategy = BackoffStrategy(backoff = 1.seconds, maxLaunchDelay = 1.hour, factor = 1.15)
}