package mesosphere.mesos

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import org.apache.mesos.Protos.Resource

object VolumeConstraints {
  import ResourceHelpers._

  class VolumeConstraintsMatcher(diskResource: Resource, constraint: Constraint) {
    val field = constraint.getField
    val value = constraint.getValue
    def isMatch: Boolean = {
      if (field == "path") {
        checkHostName
      } else {
        false
      }
    }

    private def getPath: String =
      diskResource.getSourceOption.map(_.getDiskPath).getOrElse("")

    private def checkHostName: Boolean =
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
