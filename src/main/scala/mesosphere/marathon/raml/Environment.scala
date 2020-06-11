package mesosphere.marathon
package raml

/**
  * Helpers for quick environment variable construction
  */
object Environment {
  def apply(kv: (String, String)*): Map[String, EnvVarValueOrSecret] =
    kv.iterator.map { case (k, v) => k -> EnvVarValue(v) }.toMap
  def apply(env: Map[String, String]): Map[String, EnvVarValueOrSecret] = env.map { case (k, v) => k -> (EnvVarValue(v)) }

  object Implicits {
    implicit class WithSecrets(val env: Map[String, EnvVarValueOrSecret]) extends AnyVal {
      def withSecrets(nameToSecretRef: (String, String)*): Map[String, EnvVarValueOrSecret] = {
        env ++ nameToSecretRef.map {
          case (name, secretRef) =>
            name -> EnvVarSecret(secretRef)
        }
      }

      def withSecrets(namesToSecretRefs: Map[String, String]): Map[String, EnvVarValueOrSecret] = {
        env ++ namesToSecretRefs.map { case (k, secret) => k -> EnvVarSecret(secret) }
      }
    }
  }
}
