package mesosphere.marathon.raml

import mesosphere.marathon.state

import scala.collection.immutable.Map

trait EnvVarConversion {
  implicit val envVarRamlWrites: Writes[Map[String, state.EnvVarValue], EnvVars] =
    Writes { env =>
      EnvVars(
        env.map {
          case (k, state.EnvVarString(v)) => k -> EnvVarValue(v)
          case (k, state.EnvVarSecretRef(v)) => k -> EnvVarSecretRef(v)
        }
      )
    }

  implicit val envVarReads: Reads[EnvVars, Map[String, state.EnvVarValue]] =
    Reads {
      _.values.map {
        case (k, EnvVarValue(v)) => k -> state.EnvVarString(v)
        case (k, EnvVarSecretRef(v)) => k -> state.EnvVarSecretRef(v)
      }
    }
}

object EnvVarConversion extends EnvVarConversion
