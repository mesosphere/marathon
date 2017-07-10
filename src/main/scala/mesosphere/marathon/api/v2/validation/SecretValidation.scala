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

  def secretValidator(secrets: Map[String, EnvVarSecret]) = validator[(String, EnvVarValueOrSecret)] { entry =>
    entry._2 as stringify(entry._2) is isTrue("references an undefined secret"){
      case EnvVarSecret(ref: String) => secrets.contains(ref)
      case _ => true
    }
  }

  val secretEntryValidator: Validator[(String, SecretDef)] = validator[(String, SecretDef)] { t =>
    t._1 as "" is notEmpty
    t._2.source as "source" is notEmpty
  }

  val secretValidator = validator[Map[String, SecretDef]] { s =>
    s.keys is every(notEmpty)
    s.values.map(_.source) as "source" is every(notEmpty)
  }
}

object SecretValidation extends SecretValidation
