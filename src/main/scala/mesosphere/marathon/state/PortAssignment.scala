package mesosphere.marathon.state

case class PortAssignment(portName: Option[String], portIndex: Int, effectiveIpAddress: String, effectiveHostPort: Int)

object PortAssignment {
  /**
    * If you change this, please also update AppDefinition.json.
    */
  val PortNamePattern = """^[a-z0-9-]+$""".r
}
