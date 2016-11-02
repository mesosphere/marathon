package mesosphere.marathon
package raml

/**
  * Helpers for quick environment variable construction
  */
object Environment {
  def apply(kv: (String, String)*): Map[String, EnvVarValueOrSecret] =
    kv.map { case (k, v) => k -> EnvVarValue(v) }(collection.breakOut)
  def apply(env: Map[String, String]): Map[String, EnvVarValueOrSecret] = env.mapValues(EnvVarValue(_))
}
