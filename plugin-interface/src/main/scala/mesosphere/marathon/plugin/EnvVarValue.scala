package mesosphere.marathon.plugin

trait EnvVarValue

trait EnvVarString extends EnvVarValue {
  def value: String
}

trait EnvVarSecretRef extends EnvVarValue {
  def secret: String
}
