package mesosphere.marathon
package raml

/**
  * Helpers for quickly constructing port definitions
  */
object PortDefinitions {

  def apply(ports: Int*): Seq[PortDefinition] =
    ports.map(p => PortDefinition(p))(collection.breakOut)

  def apply(ports: Map[String, Int]): Seq[PortDefinition] =
    ports.map {
      case (name, port) =>
        PortDefinition(port, name = Option(name))
    }(collection.breakOut)
}
