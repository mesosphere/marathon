package mesosphere.mesos.protos

case class SetResource(
    name: String,
    items: Set[String],
    role: String = "*") extends Resource
