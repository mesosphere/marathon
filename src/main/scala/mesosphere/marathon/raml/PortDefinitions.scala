package mesosphere.marathon
package raml

/**
  * Helpers for quickly constructing port definitions
  */
object PortDefinitions {

  def apply(ports: Int*): Seq[PortDefinition] =
    ports.iterator.map(p => PortDefinition(p)).toSeq

  def apply(ports: Map[String, Int]): Seq[PortDefinition] =
    ports.iterator.map {
      case (name, port) =>
        PortDefinition(port, name = Option(name))
    }.toSeq
}
