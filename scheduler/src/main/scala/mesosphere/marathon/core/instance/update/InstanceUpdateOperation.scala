package mesosphere.marathon
package core.instance.update

import mesosphere.marathon.core.instance.Instance

trait InstanceUpdateOperation {
  def instanceId: Instance.Id

  def shortString: String = s"${this.getClass.getSimpleName} instance update operation for $instanceId"
}
