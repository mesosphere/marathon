package mesosphere.marathon
package api.v2.validation

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.raml.{ EnvVarSecret, EnvVarValueOrSecret, SecretDef }

/**
  * RAML-generated validation doesn't cover environment variable names yet
  */
trait EnvVarValidation {
  import mesosphere.marathon.api.v2.Validation._
  import EnvVarValidationMessages._

  val EnvVarNamePattern = raml.Pod.ConstraintEnvironmentKeypattern

  /**
    * @param strictNameValidation corresponds to RAML type EnvVars when true and LegacyEnvVars when false. Keeping this
    *                             until we completely phase out legacy names for apps (the proposed deprecation process
    *                             requires the ability to enforce strict name validation via feature flag). String env
    *                             var names for pod environments is already enforced by the RAML code generator.
    *                             See https://jira.mesosphere.com/browse/MARATHON-7183.
    */
  def validEnvVar(strictNameValidation: Boolean): Validator[String] = validator[String] { name =>
    name is implied(strictNameValidation)(matchRegexWithFailureMessage(EnvVarNamePattern, MustContainOnlyAlphanumeric))
    name is notEmpty
  }

  def envValidator(strictNameValidation: Boolean, secrets: Map[String, SecretDef], enabledFeatures: Set[String]): Validator[Map[String, mesosphere.marathon.raml.EnvVarValueOrSecret]] = forAll(
    validator[Map[String, EnvVarValueOrSecret]] { env =>
      env.keys is every(validEnvVar(strictNameValidation))
    },
    isTrue(UseOfSecretRefsRequiresSecretFeature) { (env: Map[String, EnvVarValueOrSecret]) =>
      // if the secrets feature is not enabled then don't allow EnvVarSecretRef's in the environment
      if (!enabledFeatures.contains(Features.SECRETS))
        env.values.count {
          case _: EnvVarSecret => true
          case _ => false
        } == 0
      else true
    },
    everyKeyValue(SecretValidation.secretValidator(secrets.map {
      case (secretName, secretValue) => secretName -> EnvVarSecret(secretValue.source)
    }))
  )
}

object EnvVarValidation extends EnvVarValidation

object EnvVarValidationMessages {
  val MustContainOnlyAlphanumeric = "must contain only alphanumeric chars or underscore, must not begin with a number, and must be 254 chars or less"
  val UseOfSecretRefsRequiresSecretFeature = "use of secret-references in the environment requires the secrets feature to be enabled"
}
