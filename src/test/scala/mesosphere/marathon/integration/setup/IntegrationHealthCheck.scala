package mesosphere.marathon
package integration.setup

import com.typesafe.scalalogging.StrictLogging
import java.util.concurrent.atomic.AtomicBoolean
import mesosphere.marathon.state.PathId

import scala.concurrent.duration._

/**
  * Health check helper to define health behaviour of launched applications
  */
class IntegrationHealthCheck(val appId: PathId, val versionId: String, var state: Boolean)
    extends StrictLogging {

  case class HealthStatusChange(deadLine: Deadline, state: Boolean)
  private[this] var changes = List.empty[HealthStatusChange]
  private[this] var healthAction = (check: IntegrationHealthCheck) => {}
  val pinged = new AtomicBoolean(state)

  def afterDelay(delay: FiniteDuration, state: Boolean): Unit = {
    val item = HealthStatusChange(delay.fromNow, state)
    def insert(ag: List[HealthStatusChange]): List[HealthStatusChange] = {
      if (ag.isEmpty || item.deadLine < ag.head.deadLine) item :: ag
      else ag.head :: insert(ag.tail)
    }
    changes = insert(changes)
  }

  def withHealthAction(fn: (IntegrationHealthCheck) => Unit): this.type = {
    healthAction = fn
    this
  }

  def healthy: Boolean = {
    healthAction(this)
    pinged.set(true)
    val (past, future) = changes.partition(_.deadLine.isOverdue())
    state = past.lastOption.fold(state)(_.state)
    changes = future
    logger.debug(s"Get health state from: app=$appId -> $state")
    state
  }

  def forVersion(versionId: String, state: Boolean) = {
    new IntegrationHealthCheck(appId, versionId, state)
  }
}

