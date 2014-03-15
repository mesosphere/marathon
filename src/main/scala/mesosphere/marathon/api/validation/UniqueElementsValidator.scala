package mesosphere.marathon.api.validation

import javax.validation.{ConstraintValidator, ConstraintValidatorContext}

/**
 * This validator accepts objects of type Iterable[_] where all of the
 * elements are unique, and Option[Iterable[_]] where the wrapped collection's
 * elements are unique.
 */
class UniqueElementsValidator
  extends ConstraintValidator[UniqueElements, Any] {

  def initialize(annotation: UniqueElements): Unit = {}

  def isValid(obj: Any, context: ConstraintValidatorContext): Boolean =
    obj match {
      case opt: Option[_] => opt.forall { isValid(_, context) }
      case it: Iterable[_] => it.size == it.toSeq.distinct.size
      case _ => false
    }

}