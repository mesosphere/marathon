package mesosphere.marathon.api.v2.conversion

import mesosphere.marathon.raml.EnvVars
import mesosphere.marathon.{ raml, state }

import scala.collection.immutable.Map

trait EnvVarConversion {

  implicit val fromAPIObjectToEnvironment = Converter { envVars: EnvVars =>
    def toEnvValue(envVarValue: raml.EnvVarValueOrSecret): state.EnvVarValue = envVarValue match {
      case raml.EnvVarValue(value) => state.EnvVarString(value)
      case raml.EnvVarSecretRef(secret) => state.EnvVarSecretRef(secret)
    }
    if(envVars.values.isEmpty) None else Some(envVars.values.map{ case (k, v) => k -> toEnvValue(v) })
  }

  implicit val fromEnvironmentToAPIObject = Converter { envMap: Map[String, state.EnvVarValue] =>
    def toEnvValue(env: state.EnvVarValue): raml.EnvVarValueOrSecret = env match {
      case state.EnvVarString(value) => raml.EnvVarValue(value)
      case state.EnvVarSecretRef(secret) => raml.EnvVarSecretRef(secret)
    }
    if(envMap.isEmpty) None else Some(EnvVars(envMap.map { case (k, v) => k -> toEnvValue(v) }))
  }
}
