package mesosphere.marathon.api.serialization

import mesosphere.marathon.Protos
import mesosphere.marathon.state.{ EnvVarValue, EnvVarSecretRef }

object EnvVarRefSerializer {
  def toProto(envVar: (String, EnvVarValue)): Option[Protos.EnvVarReference] = envVar match {
    case (name: String, secretRef: EnvVarSecretRef) => Some(
      Protos.EnvVarReference.newBuilder
      .setName(name)
      .setType(Protos.EnvVarReference.Type.SECRET)
      .setSecretRef(Protos.EnvVarSecretRef.newBuilder.setSecretId(secretRef.secret))
      .build)
    case _ => None
  }

  def fromProto(ref: Protos.EnvVarReference): Option[(String, EnvVarValue)] =
    if (ref.getType == Protos.EnvVarReference.Type.SECRET && ref.hasSecretRef)
      Some(ref.getName -> EnvVarSecretRef(ref.getSecretRef.getSecretId))
    else None
}
