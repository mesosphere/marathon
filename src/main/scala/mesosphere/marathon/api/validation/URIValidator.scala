package mesosphere.marathon.api.validation

import javax.validation.{ConstraintValidatorContext, ConstraintValidator}
import scala.util.Try

/**
 * Validate that the character sequence (e.g. string) is a valid URI.
 */
class URIValidator extends ConstraintValidator[URI, CharSequence] {

  override def isValid(value: CharSequence, context: ConstraintValidatorContext): Boolean = {
    Try(new java.net.URI(value.toString)).isSuccess
  }

  override def initialize(constraintAnnotation: URI) {} //no initialization needed
}
