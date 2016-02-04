package mesosphere.marathon.state

import com.wix.accord.dsl._
import mesosphere.marathon.Protos.ResidencyDefinition.TaskLostBehavior

case class Residency(relaunchEscalationTimeoutSeconds: Long, taskLostBehavior: TaskLostBehavior)

object Residency {
  val defaultTaskLostBehaviour = TaskLostBehavior.WAIT_FOREVER
  val defaultRelaunchEscalationTimeoutSeconds: Long = 3600

  implicit val residencyValidator = validator[Residency] { residency =>
    residency.relaunchEscalationTimeoutSeconds >= 0
  }

}
