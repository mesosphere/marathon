package mesosphere.marathon
package state

import com.wix.accord.dsl._
import mesosphere.marathon.Protos.ResidencyDefinition.TaskLostBehavior
import mesosphere.marathon.raml.RamlConversions

case class Residency(relaunchEscalationTimeoutSeconds: Long, taskLostBehavior: TaskLostBehavior)

object Residency {
  def default: Residency = Residency(defaultRelaunchEscalationTimeoutSeconds, defaultTaskLostBehaviour)

  def defaultTaskLostBehaviour: TaskLostBehavior = RamlConversions.fromRaml(raml.AppResidency.DefaultTaskLostBehavior)
  def defaultRelaunchEscalationTimeoutSeconds: Long = raml.AppResidency.DefaultRelaunchEscalationTimeoutSeconds

  implicit val residencyValidator = validator[Residency] { residency =>
    residency.relaunchEscalationTimeoutSeconds >= 0
  }
}
