package mesosphere.marathon
package raml

import mesosphere.marathon.state

import scala.collection.immutable.Map

trait EnvVarConversion {
  implicit val envVarRamlWrites: Writes[Map[String, state.EnvVarValue], Map[String, EnvVarValueOrSecret]] =
    Writes {
      _.map {
        case (name, state.EnvVarString(v)) => name -> EnvVarValue(v)
        case (name, state.EnvVarSecretRef(v)) => name -> EnvVarSecretRef(v)
      }
    }

  implicit val envVarReads: Reads[Map[String, EnvVarValueOrSecret], Map[String, state.EnvVarValue]] =
    Reads {
      _.map {
        case (name, EnvVarValue(v)) => name -> state.EnvVarString(v)
        case (name, EnvVarSecretRef(v)) => name -> state.EnvVarSecretRef(v)
      }
    }
}

object EnvVarConversion extends EnvVarConversion
