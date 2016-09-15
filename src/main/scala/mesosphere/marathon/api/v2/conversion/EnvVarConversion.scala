package mesosphere.marathon.api.v2.conversion

import mesosphere.marathon.raml.EnvVars
import mesosphere.marathon.state.EnvVarValue
import mesosphere.marathon.{ raml, state }

import scala.collection.immutable.Map

trait EnvVarConversion {

  implicit val fromAPIObjectToEnvironment: Converter[EnvVars,Map[String,EnvVarValue]] = Converter { envVars: EnvVars =>
    def toEnvValue(envVarValue: raml.EnvVarValueOrSecret): state.EnvVarValue = envVarValue match {
      case raml.EnvVarValue(value) => state.EnvVarString(value)
      case raml.EnvVarSecretRef(secret) => state.EnvVarSecretRef(secret)
    }
    envVars.values.map{ case (k, v) => k -> toEnvValue(v) }
  }

  implicit val fromEnvironmentToAPIObject: Converter[Map[String,EnvVarValue],EnvVars] = Converter {
    envMap: Map[String, state.EnvVarValue] =>
      def toEnvValue(env: state.EnvVarValue): raml.EnvVarValueOrSecret = env match {
        case state.EnvVarString(value) => raml.EnvVarValue(value)
        case state.EnvVarSecretRef(secret) => raml.EnvVarSecretRef(secret)
      }
      EnvVars(envMap.map { case (k, v) => k -> toEnvValue(v) })
    }
}
