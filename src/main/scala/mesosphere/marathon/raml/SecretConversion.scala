package mesosphere.marathon
package raml

import mesosphere.marathon.state.Secret

trait SecretConversion {
  implicit val podSecretReader: Reads[Map[String, SecretDef], Map[String, Secret]] =
    Reads(_.map { case (name, value) => name -> Secret(value.source) })

  implicit val podSecretWriter: Writes[Map[String, Secret], Map[String, SecretDef]] =
    Writes(_.map { case (name, value) => name -> SecretDef(value.source) })

  implicit val secretProtoRamlWriter: Writes[Protos.Secret, (String, SecretDef)] = Writes {
    secret => secret.getId -> SecretDef(secret.getSource)
  }
}
