package mesosphere.marathon.core.pod

/**
  * Specifies a constraint.
  * @param field the name of the field that shall be checked.
  * @param operator the operator defining the type of constraint
  * @param value The value, if any, of the constraint
  */
case class Constraint(field: String, operator: Operator, value: Option[String])

sealed trait Operator
case object UNIQUE extends Operator
case object CLUSTER extends Operator
case object GROUP_BY extends Operator
case object LIKE extends Operator
case object UNLIKE extends Operator
case object MAX_PER extends Operator
