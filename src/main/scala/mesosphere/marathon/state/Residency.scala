package mesosphere.marathon
package state

import com.wix.accord.dsl._
import mesosphere.marathon.Protos.ResidencyDefinition.TaskLostBehavior

case class Residency(relaunchEscalationTimeoutSeconds: Long, taskLostBehavior: TaskLostBehavior)

object Residency {
  def default: Residency = Residency(defaultRelaunchEscalationTimeoutSeconds, defaultTaskLostBehaviour)

  def defaultTaskLostBehaviour: TaskLostBehavior = raml.Residency.DefaultTaskLostBehavior.fromRaml
  def defaultRelaunchEscalationTimeoutSeconds: Long = raml.Residency.DefaultRelaunchEscalationTimeoutSeconds

  implicit val residencyValidator = validator[Residency] { residency =>
    residency.relaunchEscalationTimeoutSeconds >= 0
  }
}
