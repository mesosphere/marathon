package mesosphere.marathon.raml

trait RamlConversions extends ConstraintConversion with ContainerConversion
  with EnvVarConversion with NetworkConversion with PodConversion with PodStatusConversion

object RamlConversions extends RamlConversions
