package mesosphere.marathon
package api.v2.validation

import com.wix.accord.Validator
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.raml.{ EnvVarSecretRef, EnvVarValueOrSecret, SecretDef }

trait SecretValidation {
  import Validation._

  def stringify(ref: EnvVarValueOrSecret): String =
    ref match {
      case secretRef: EnvVarSecretRef => secretRef.secret
      case _ => ref.toString // this should never be called; if it is, validation output will not be friendly
    }

  def secretRefValidator(secrets: Map[String, SecretDef]) = validator[(String, EnvVarValueOrSecret)] { entry =>
    entry._2 as stringify(entry._2) is isTrue("references an undefined secret"){
      case ref: EnvVarSecretRef => secrets.contains(ref.secret)
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
