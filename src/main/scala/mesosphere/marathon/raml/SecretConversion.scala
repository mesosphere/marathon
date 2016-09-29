package mesosphere.marathon.raml

import mesosphere.marathon.state.Secret

trait SecretConversion {
  implicit val podSecretReader: Reads[Map[String, SecretDef], Map[String, Secret]] =
    Reads(_.mapValues(v => Secret(v.source)))

  implicit val podSecretWriter: Writes[Map[String, Secret], Map[String, SecretDef]] =
    Writes(_.mapValues(s => SecretDef(s.source)))
}
