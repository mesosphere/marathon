package mesosphere.marathon
package raml

/**
  * Helpers for quick environment variable construction
  */
object Environment {
  def apply(kv: (String, String)*): Map[String, EnvVarValueOrSecret] =
    kv.map { case (k, v) => k -> EnvVarValue(v) }(collection.breakOut)
  def apply(env: Map[String, String]): Map[String, EnvVarValueOrSecret] = env.mapValues(EnvVarValue(_))

  object Implicits {
    implicit class WithSecrets(val env: Map[String, EnvVarValueOrSecret]) extends AnyVal {
      def withSecrets(nameToSecretRef: (String, String)*): Map[String, EnvVarValueOrSecret] = {
        env ++ nameToSecretRef.map {
          case (name, secretRef) =>
            name -> EnvVarSecretRef(secretRef)
        }
      }

      def withSecrets(namesToSecretRefs: Map[String, String]): Map[String, EnvVarValueOrSecret] = {
        env ++ namesToSecretRefs.mapValues(EnvVarSecretRef(_))
      }
    }
  }
}
