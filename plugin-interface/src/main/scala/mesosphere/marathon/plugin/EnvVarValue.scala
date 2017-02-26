package mesosphere.marathon
package plugin

/** EnvVarValue is an abstract representation of the value of some environment variable */
trait EnvVarValue

/** EnvVarString contains the text content of some environment variable */
trait EnvVarString extends EnvVarValue {
  def value: String
}

/** EnvVarSecretRef refers to a secret ID that's declared within an app definition */
trait EnvVarSecretRef extends EnvVarValue {
  def secret: String
}
