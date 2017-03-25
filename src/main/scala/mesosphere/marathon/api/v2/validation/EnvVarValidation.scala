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

  val EnvVarNamePattern = """^[a-zA-Z_][a-zA-Z0-9_]*$""".r

  val validEnvVarName: Validator[String] = validator[String] { name =>
    name should matchRegexWithFailureMessage(
      EnvVarNamePattern,
      "must contain only alphanumeric chars or underscore, and must not begin with a number")
    name.length should be > 0
    name.length should be < 255
  }

  def envValidator(secrets: Map[String, SecretDef], enabledFeatures: Set[String]) = forAll(
    validator[Map[String, EnvVarValueOrSecret]] { env =>
      env.keys is every(validEnvVarName)
    },
    isTrue("use of secret-references in the environment requires the secrets feature to be enabled") { (env: Map[String, EnvVarValueOrSecret]) =>
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
