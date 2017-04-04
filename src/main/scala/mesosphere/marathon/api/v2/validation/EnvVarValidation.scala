package mesosphere.marathon
package api.v2.validation

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.raml.{ EnvVarSecretRef, EnvVarValueOrSecret, SecretDef }

/**
  * RAML-generated validation doesn't cover environment variable names yet
  */
trait EnvVarValidation {
  import mesosphere.marathon.api.v2.Validation._
  import EnvVarValidationMessages._

  val EnvVarNamePattern = """^[a-zA-Z_][a-zA-Z0-9_]*$""".r

  /**
    * @param strictNameValidation corresponds to RAML type EnvVars when true and LegacyEnvVars when false.
    */
  def validEnvVar(strictNameValidation: Boolean): Validator[(String, EnvVarValueOrSecret)] = {

    val validName = validator[String] { name =>
      name is implied(strictNameValidation)(matchRegexWithFailureMessage(EnvVarNamePattern, MustContainOnlyAlphanumeric))
      name is notEmpty
    } and isTrue[String](VariableNameTooLong) { _.length < 255 }

    validator[(String, EnvVarValueOrSecret)] { t =>
      // use of "value" relies on special behavior in Validation that humanizes generated error messages
      t._1 as "value" is validName
    }
  }

  def envValidator(strictNameValidation: Boolean, secrets: Map[String, SecretDef], enabledFeatures: Set[String]) = forAll(
    validator[Map[String, EnvVarValueOrSecret]] { env =>
      env is every(validEnvVar(strictNameValidation))
    },
    isTrue(UseOfSecretRefsRequiresSecretFeature) { (env: Map[String, EnvVarValueOrSecret]) =>
      // if the secrets feature is not enabled then don't allow EnvVarSecretRef's in the environment
      if (!enabledFeatures.contains(Features.SECRETS))
        env.values.count {
          case _: EnvVarSecretRef => true
          case _ => false
        } == 0
      else true
    },
    every(SecretValidation.secretRefValidator(secrets))
  )
}

object EnvVarValidation extends EnvVarValidation

object EnvVarValidationMessages {
  val MustContainOnlyAlphanumeric = "must contain only alphanumeric chars or underscore, and must not begin with a number"
  val UseOfSecretRefsRequiresSecretFeature = "use of secret-references in the environment requires the secrets feature to be enabled"
  val VariableNameTooLong = "variable name must be 254 chars or less"
}
