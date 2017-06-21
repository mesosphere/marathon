package mesosphere.marathon
package raml

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

  implicit val appConstraintRamlReader: Reads[Seq[String], Protos.Constraint] = Reads { raw =>
    // this is not a substite for validation, but does ensure that we're not translating invalid operators
    def validOperator(op: String): Boolean = ConstraintConversion.ValidOperators.contains(op)
    val result: Protos.Constraint = (raw.lift(0), raw.lift(1), raw.lift(2)) match {
      case (Some(field), Some(op), None) if validOperator(op) =>
        Protos.Constraint.newBuilder()
          .setField(field)
          .setOperator(Protos.Constraint.Operator.valueOf(op))
          .build()
      case (Some(field), Some(op), Some(value)) if validOperator(op) =>
        Protos.Constraint.newBuilder()
          .setField(field)
          .setOperator(Protos.Constraint.Operator.valueOf(op))
          .setValue(value)
          .build()
      case _ => throw SerializationFailedException(s"illegal constraint specification ${raw.mkString(",")}")
    }
    result
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

  implicit val constraintToSeqStringWrites: Writes[Protos.Constraint, Seq[String]] = Writes { constraint =>
    val builder = Seq.newBuilder[String]
    builder += constraint.getField
    builder += constraint.getOperator.name
    if (constraint.hasValue) builder += constraint.getValue
    builder.result()
  }
}

object ConstraintConversion extends ConstraintConversion {
  val ValidOperators: Set[String] = Protos.Constraint.Operator.values().map(_.toString)(collection.breakOut)
}
