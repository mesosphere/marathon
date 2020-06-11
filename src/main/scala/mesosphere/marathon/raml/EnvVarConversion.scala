package mesosphere.marathon
package raml

import org.apache.mesos.{Protos => Mesos}

import scala.collection.immutable.Map

trait EnvVarConversion {
  implicit val envVarRamlWrites: Writes[Map[String, state.EnvVarValue], Map[String, EnvVarValueOrSecret]] =
    Writes {
      _.map {
        case (k, state.EnvVarString(v)) => k -> EnvVarValue(v)
        case (k, state.EnvVarSecretRef(secret: String)) => k -> EnvVarSecret(secret)
      }
    }

  implicit val envVarReads: Reads[Map[String, EnvVarValueOrSecret], Map[String, state.EnvVarValue]] =
    Reads {
      _.map {
        case (k, EnvVarValue(v)) => k -> state.EnvVarString(v)
        case (k, EnvVarSecret(secret: String)) => k -> state.EnvVarSecretRef(secret)
      }
    }

  implicit val envProtoRamlWrites: Writes[(Seq[Mesos.Environment.Variable], Seq[Protos.EnvVarReference]), Map[String, EnvVarValueOrSecret]] =
    Writes {
      case (env, refs) =>
        val vanillaEnv: Map[String, EnvVarValueOrSecret] = env.iterator.map { item =>
          item.getName -> EnvVarValue(item.getValue)
        }.toMap

        vanillaEnv ++ refs.withFilter(_.getType == Protos.EnvVarReference.Type.SECRET).map { secretRef =>
          secretRef.getName -> EnvVarSecret(secretRef.getSecretRef.getSecretId)
        }
    }
}

object EnvVarConversion extends EnvVarConversion
