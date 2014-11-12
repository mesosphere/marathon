package mesosphere.marathon.api.validation

import mesosphere.marathon.state.AppDefinition.RANDOM_PORT_VALUE
import javax.validation.{ ConstraintValidator, ConstraintValidatorContext }

/**
  * This validator accepts objects of type Iterable[Int] where all of the
  * elements are unique, and Option[Iterable[Int]] where the wrapped collection's
  * elements are unique.
  */
class PortsArrayValidator
    extends ConstraintValidator[PortsArray, Any] {

  def initialize(annotation: PortsArray): Unit = {}

  def isValid(obj: Any, context: ConstraintValidatorContext): Boolean =
    obj match {
      case opt: Option[_] => opt.forall { isValid(_, context) }
      case it: Iterable[_] => {
        val withoutRandoms = it.toSeq.filterNot { _ == RANDOM_PORT_VALUE }
        withoutRandoms.size == withoutRandoms.distinct.size
      }
      case _ => false
    }

}
