package mesosphere.marathon.state

/**
  * @param portName name of the port
  * @param portIndex index of the port, used for example to define health checks
  * @param effectiveIpAddress ip address on which the port can be reached (can be an agent's IP or an IP-per-Task)
  * @param effectivePort resolved non-dynamic port. The task is reachable under effectiveIpAddress:effectivePort.
  */
case class PortAssignment(portName: Option[String], portIndex: Int, effectiveIpAddress: String, effectivePort: Int)

object PortAssignment {
  /**
    * If you change this, please also update AppDefinition.json.
    */
  val PortNamePattern = """^[a-z0-9-]+$""".r
}
