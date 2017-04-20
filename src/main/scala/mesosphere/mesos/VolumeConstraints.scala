package mesosphere.mesos

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.state.DiskSource
import mesosphere.marathon.tasks.ResourceUtil.RichResource
import org.apache.mesos.Protos.Resource

object VolumeConstraints {

  class VolumeConstraintsMatcher(diskResource: Resource, constraint: Constraint) {
    val field = constraint.getField
    val value = constraint.getValue
    def isMatch: Boolean = {
      if (field == "path") {
        checkPath
      } else {
        false
      }
    }

    val diskSource = DiskSource.fromMesos(diskResource.getDiskSourceOption)

    private def getPath: String =
      diskSource.path.getOrElse("")

    private def checkPath: Boolean =
      constraint.getOperator match {
        case Operator.LIKE => getPath.matches(value)
        case Operator.UNLIKE => !getPath.matches(value)
        case _ => false
      }
  }

  def meetsConstraint(diskResource: Resource, constraint: Constraint): Boolean = {
    new VolumeConstraintsMatcher(diskResource: Resource, constraint: Constraint).isMatch
  }

  def meetsAllConstraints(diskResource: Resource, constraints: Set[Constraint]): Boolean = {
    constraints.forall(meetsConstraint(diskResource, _))
  }
}
