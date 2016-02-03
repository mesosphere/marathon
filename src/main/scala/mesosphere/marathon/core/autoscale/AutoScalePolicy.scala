package mesosphere.marathon.core.autoscale

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.{ AutoScalePolicyDefinition, AppDefinition }

import scala.concurrent.Future

case class AutoScaleResult(target: Int, killTasks: Seq[MarathonTask])

/**
  * Definition of an AutoScalePolicy.
  */
trait AutoScalePolicy {

  /**
    * Unique name of the auto scaling policy.
    * Used in the definition to reference this policy.
    * @return the unique name of this policy.
    */
  def name: String

  /**
    * Compute the scaling result, based on given definition for given app and given running tasks.
    * @param definition the user defined parameter for this policy
    * @param app the application to auto scale
    * @param tasks the currently running tasks
    * @return The desired instance count with tasks to kill.
    */
  def scale(definition: AutoScalePolicyDefinition,
            app: AppDefinition,
            tasks: Seq[MarathonTask]): Future[AutoScaleResult]

}
