package mesosphere.marathon.api.serialization

import mesosphere.marathon.Protos
import mesosphere.marathon.state.Secret

object SecretsSerializer {
  def toProto(namedSecret: (String, Secret)): Protos.Secret = namedSecret match {
    case (id, secret) => Protos.Secret.newBuilder
      .setId(id)
      .setSource(secret.source)
      .build
  }

  def fromProto(proto: Protos.Secret): (String, Secret) =
    proto.getId -> Secret(source = proto.getSource)
}
