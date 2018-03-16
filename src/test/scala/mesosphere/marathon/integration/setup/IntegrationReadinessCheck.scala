package mesosphere.marathon
package integration.setup

import com.typesafe.scalalogging.StrictLogging
import java.util.concurrent.atomic.AtomicBoolean
import mesosphere.marathon.state.PathId

/**
  * Readiness check helper to define readiness behaviour of launched applications
  */
class IntegrationReadinessCheck(val appId: PathId, val versionId: String, val taskId: Option[String]) extends StrictLogging {

  val isReady = new AtomicBoolean(false)
  val wasCalled = new AtomicBoolean(false)

  /**
    * Query readiness.
    * @return Whether app is supposed to be ready or not.
    */
  def call(): Boolean = {
    val state = isReady.get
    wasCalled.set(true)
    logger.info(s"Got readiness check call from: app=$appId -> $state")
    state
  }

  override def toString(): String = s"IntegrationReadinessCheck(appId=$appId, versionId=$versionId, taskId=$taskId)"
}
