package mesosphere.marathon.state

import mesosphere.marathon.plugin

trait EnvVarValue extends plugin.EnvVarValue

case class EnvVarString(value: String) extends EnvVarValue
case class EnvVarSecretRef(secret: String) extends EnvVarValue

object EnvVarValue {
  import scala.language.implicitConversions
  implicit def stringConversion(s: String): EnvVarValue = EnvVarString(s)
  implicit def mapConversion(m: Map[String, String]): Map[String, EnvVarValue] =
    m.map { case (k, v) => k -> EnvVarString(v) }
}
