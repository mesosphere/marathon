package mesosphere.marathon.raml
import mesosphere.marathon.Protos

trait ConstraintConversion {
  implicit val constraintRamlReader: Reads[Constraint, Protos.Constraint] = Reads { raml =>
    val operator = raml.operator match {
      case ConstraintOperator.Unique => Protos.Constraint.Operator.UNIQUE
      case ConstraintOperator.Cluster => Protos.Constraint.Operator.CLUSTER
      case ConstraintOperator.GroupBy => Protos.Constraint.Operator.GROUP_BY
      case ConstraintOperator.Like => Protos.Constraint.Operator.LIKE
      case ConstraintOperator.Unlike => Protos.Constraint.Operator.UNLIKE
      case ConstraintOperator.MaxPer => Protos.Constraint.Operator.MAX_PER
    }

    val builder = Protos.Constraint.newBuilder().setField(raml.fieldName).setOperator(operator)
    raml.value.foreach(builder.setValue)
    builder.build()
  }

  implicit val constraintRamlWriter: Writes[Protos.Constraint, Constraint] = Writes { c =>
    val operator = c.getOperator match {
      case Protos.Constraint.Operator.UNIQUE => ConstraintOperator.Unique
      case Protos.Constraint.Operator.CLUSTER => ConstraintOperator.Cluster
      case Protos.Constraint.Operator.GROUP_BY => ConstraintOperator.GroupBy
      case Protos.Constraint.Operator.LIKE => ConstraintOperator.Like
      case Protos.Constraint.Operator.UNLIKE => ConstraintOperator.Unlike
      case Protos.Constraint.Operator.MAX_PER => ConstraintOperator.MaxPer
    }
    Constraint(c.getField, operator, Option(c.getValue))
  }
}

object ConstraintConversion extends ConstraintConversion
