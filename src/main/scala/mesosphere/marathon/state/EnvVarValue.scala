package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.plugin

sealed trait EnvVarValue extends plugin.EnvVarValue {
  val valueValidator = new Validator[EnvVarValue] {
    override def apply(v: EnvVarValue) =
      v match {
        case s: EnvVarString    => validate(s)(EnvVarString.valueValidator)
        case r: EnvVarSecretRef => validate(r)(EnvVarSecretRef.valueValidator)
      }
  }
}

sealed trait EnvVarRef extends EnvVarValue {
  val appValidator: Validator[AppDefinition]
}

case class EnvVarString(value: String) extends EnvVarValue

case class EnvVarSecretRef(secret: String) extends EnvVarRef {
  override lazy val appValidator = EnvVarSecretRef.appValidator
}

object EnvVarValue {
  import scala.language.implicitConversions

  // automatically upgrade strings where needed
  implicit def stringConversion(s: String): EnvVarValue = EnvVarString(s)

  // automatically upgrade normal string,string maps
  implicit def mapConversion(m: Map[String, String]): Map[String, EnvVarValue] =
    m.map { case (k, v) => k -> EnvVarString(v) }

  // forward implicit validation to the internal API we've defined
  implicit def valueValidator: Validator[EnvVarValue] = validator[EnvVarValue] { v =>
    v is valid(v.valueValidator)
  }

  /** @return a validator that checks the validity of a container given the related volume providers */
  def validApp(): Validator[AppDefinition] = new Validator[AppDefinition] {
    def apply(app: AppDefinition) = {
      val refValidators = app.env.collect{ case (s: String, sr: EnvVarRef) => sr.appValidator }.toSet
      refValidators.map(validate(app)(_)).fold(Success)(_ and _)
    }
  }
}

object EnvVarString {
  import com.wix.accord.combinators.NilValidator
  lazy val valueValidator = new NilValidator[EnvVarString]
}

object EnvVarSecretRef {
  import mesosphere.marathon.api.v2.Validation._

  lazy val valueValidator = validator[EnvVarSecretRef] { ref =>
    ref.secret is notEmpty
  }

  lazy val appValidator = {
    val haveAllSecretRefsDefined: Validator[AppDefinition] =
      isTrue[AppDefinition](
        (app: AppDefinition) =>
          s"All secret-references must reference secret identifiers declared within app [${app.id}]"
      ) { app =>
          val secretIDs = app.secrets.keys.toSet
          val secretRefs = app.env.collect{ case (varname: String, sr: EnvVarSecretRef) => sr.secret }.toSet
          val unknownIDs = secretRefs -- secretIDs
          unknownIDs.isEmpty
        }

    validator[AppDefinition] { app =>
      app should haveAllSecretRefsDefined
    }
  }
}
