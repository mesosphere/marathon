package mesosphere.marathon
package api.v2.validation

import com.wix.accord.dsl._
import com.wix.accord._
import mesosphere.marathon.api.v2.Validation

// TODO(jdef) remove this once redundant model validation rules are gone
@SuppressWarnings(Array("all")) // wix breaks stuff
trait NameValidation {
  import Validation._

  val NamePattern = """^[a-z0-9]([-a-z0-9]*[a-z0-9])?$""".r

  val validName: Validator[String] = validator[String] { name =>
    name should matchRegexWithFailureMessage(
      NamePattern,
      "must contain only alphanumeric chars or hyphens, and must begin with a letter")
    name.length should be > 0
    name.length should be < 64
  }
}

object NameValidation extends NameValidation
