package mesosphere.marathon
package raml

import mesosphere.marathon.state.Secret
import org.apache.mesos.{ Protos => Mesos }

import scala.collection.immutable.Map

trait EnvVarConversion {
  implicit val envVarRamlWrites: Writes[Map[String, state.EnvVarValue], Map[String, EnvVarValueOrSecret]] =
    Writes {
      _.map {
        case (name, state.EnvVarString(v)) => name -> EnvVarValue(v)
        case (name, state.EnvVarSecretRef(v)) => name -> EnvVarSecret(EnvVarSecretRef(v))
        case (name, state.EnvVarSecretDef(v)) => name -> EnvVarSecret(SecretDef(v.source))
      }
    }

  implicit val envVarReads: Reads[Map[String, EnvVarValueOrSecret], Map[String, state.EnvVarValue]] =
    Reads {
      _.map {
        case (name, EnvVarValue(v)) => name -> state.EnvVarString(v)
        case (name, EnvVarSecret(v: EnvVarSecretRef)) => name -> state.EnvVarSecretRef(v.value)
        case (name, EnvVarSecret(v: SecretDef)) => name -> state.EnvVarSecretDef(Secret(v.source))
      }
    }

  implicit val envProtoRamlWrites: Writes[(Seq[Mesos.Environment.Variable], Seq[Protos.EnvVarReference]), Map[String, EnvVarValueOrSecret]] =
    Writes {
      case (env, refs) =>
        val vanillaEnv: Map[String, EnvVarValueOrSecret] = env.map { item =>
          item.getName -> EnvVarValue(item.getValue)
        }(collection.breakOut)

        vanillaEnv ++ refs.withFilter(_.getType == Protos.EnvVarReference.Type.SECRET).map { secretRef =>
          secretRef.getName -> EnvVarSecret(EnvVarSecretRef(secretRef.getSecretRef.getSecretId))
        }
    }
}

object EnvVarConversion extends EnvVarConversion
