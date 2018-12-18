package mesosphere.marathon
package state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.plugin

trait EnvVarValue extends plugin.EnvVarValue {
  val valueValidator = new Validator[EnvVarValue] {
    override def apply(v: EnvVarValue) =
      v match {
        case s: EnvVarString => validate(s)(EnvVarString.valueValidator)
        case r: EnvVarSecretRef => validate(r)(EnvVarSecretRef.valueValidator)
      }
  }
}

