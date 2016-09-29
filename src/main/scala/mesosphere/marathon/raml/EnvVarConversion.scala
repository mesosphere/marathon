package mesosphere.marathon.raml

import mesosphere.marathon.state

import scala.collection.immutable.Map

trait EnvVarConversion {
  implicit val envVarRamlWrites: Writes[Map[String, state.EnvVarValue], Map[String, EnvVarValueOrSecret]] =
    Writes {
      _.mapValues {
        case (state.EnvVarString(v)) => EnvVarValue(v)
        case (state.EnvVarSecretRef(v)) => EnvVarSecretRef(v)
      }
    }

  implicit val envVarReads: Reads[Map[String, EnvVarValueOrSecret], Map[String, state.EnvVarValue]] =
    Reads {
      _.mapValues {
        case EnvVarValue(v) => state.EnvVarString(v)
        case EnvVarSecretRef(v) => state.EnvVarSecretRef(v)
      }
    }
}

object EnvVarConversion extends EnvVarConversion
