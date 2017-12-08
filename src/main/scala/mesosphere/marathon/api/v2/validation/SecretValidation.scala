package mesosphere.marathon
package api.v2.validation

import com.wix.accord.Validator
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.raml.{ EnvVarSecret, EnvVarValue, EnvVarValueOrSecret, SecretDef }

trait SecretValidation {
  import Validation._

  def stringify(ref: EnvVarValueOrSecret): String =
    ref match {
      case EnvVarSecret(ref: String) => ref
      case EnvVarValue(value: String) => value // this should never be called; if it is, validation output will not be friendly
    }

  def secretValidator(secrets: Map[String, EnvVarSecret]) = validator[EnvVarValueOrSecret] { entry =>
    entry as "secret" is isTrue("references an undefined secret"){
      case EnvVarSecret(ref: String) => secrets.contains(ref)
      case _ => true
    }
  }

  private val secretEntryValidator: Validator[SecretDef] = validator[SecretDef] { t =>
    t.source is notEmpty
  }

  val secretValidator = validator[Map[String, SecretDef]] { s =>
    s.keys is every(notEmpty)
    s is everyKeyValue(secretEntryValidator)
  }
}

object SecretValidation extends SecretValidation
