package mesosphere.marathon
package api.v2.validation

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation

// TODO(jdef) remove this once redundant model validation rules are gone
@SuppressWarnings(Array("all")) // wix breaks stuff
trait NameValidation {
  import Validation._

  val NamePattern = raml.PodContainer.ConstraintNamePattern

  val validName: Validator[String] = validator[String] { name =>
    name should matchRegexWithFailureMessage(
      NamePattern,
      "must contain only alphanumeric chars or hyphens, and must begin with a letter")
    name.length should be >= raml.PodContainer.ConstraintNameMinlength
    name.length should be <= raml.PodContainer.ConstraintNameMaxlength
  }
}

object NameValidation extends NameValidation
