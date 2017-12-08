package mesosphere.marathon
package api.v2.validation

import com.wix.accord.Validator
import com.wix.accord.dsl._
import mesosphere.marathon.raml.{ EnvVarSecret, EnvVarValue, EnvVarValueOrSecret, Environment, SecretDef }
import mesosphere.{ UnitTest, ValidationTestLike }

class EnvVarValidationTest extends UnitTest with ValidationTestLike {

  import EnvVarValidationMessages._

  "EnvVarValidation" when {
    "an environment is validated" should {

      def compliantEnv(title: String, m: Map[String, EnvVarValueOrSecret], strictNameValidation: Boolean = true): Unit = {
        s"$title is compliant with validation rules" in new Fixtures(strictNameValidation) {
          validator(m) should be(aSuccess)
        }
      }

      behave like compliantEnv("empty env", Map.empty)
      behave like compliantEnv("singleton env", Environment("foo" -> "bar"))
      behave like compliantEnv("mixed case env", Environment("foo" -> "bar", "FOO" -> "BAR"))
      behave like compliantEnv("underscore env", Environment("_" -> "x"))
      behave like compliantEnv("alpha numerical env", Environment("a_1_" -> "x"))
      behave like compliantEnv("whitespace env", Environment(" " -> "x"), strictNameValidation = false)
      behave like compliantEnv("hyphen env", Environment("-" -> "x"), strictNameValidation = false)
      behave like compliantEnv("numerical env", Environment("9" -> "x"), strictNameValidation = false)

      "fail with a numerical env variable name" in new Fixtures {
        validator(Environment("9" -> "x")) should haveViolations(
          "/keys(0)" -> MustContainOnlyAlphanumeric)
      }

      def failsWhenExpected(subtitle: String, strict: Boolean): Unit = {
        s"fail with empty variable name $subtitle" in new Fixtures(strict) {
          validator(Environment("" -> "x")) should haveViolations(
            "/keys(0)" -> "must not be empty")
          if (strict) validator(Environment("" -> "x")) should haveViolations("/keys(0)" -> MustContainOnlyAlphanumeric)
        }

        s"fail with too long variable name $subtitle" in new Fixtures(strict) {
          val name = "x" * 255
          if (strict) {
            validator(Environment(name -> "x")) should haveViolations("/keys(0)" -> MustContainOnlyAlphanumeric)
          } else {
            validator(Environment(name -> "x")) should be(aSuccess)
          }
        }
      }

      behave like failsWhenExpected("(strict)", strict = true)
      behave like failsWhenExpected("(non-strict)", strict = false)
    }

    "validating secrets" should {
      "fail when an undefined secret is referenced" in new Fixtures(secrets = true) {
        val env = Map[String, EnvVarValueOrSecret](
          "VALIDVAR" -> EnvVarValue("validvalue"),
          "INVALIDVAR" -> EnvVarSecret("undefined-secret")
        )

        validator(env) should haveViolations("/INVALIDVAR/secret" -> "references an undefined secret")
      }
    }
  }

  class Fixtures(strictNameValidation: Boolean = true, secrets: Boolean = false) {
    val definedSecrets: Map[String, SecretDef] = if (secrets)
      Map("secret" -> SecretDef("var"))
    else
      Map.empty

    val enabledFeatures: Set[String] = if (secrets)
      Set(Features.SECRETS)
    else
      Set.empty

    val validator: Validator[Map[String, EnvVarValueOrSecret]] =
      EnvVarValidation.envValidator(
        strictNameValidation,
        secrets = definedSecrets,
        enabledFeatures = enabledFeatures)
  }
}
