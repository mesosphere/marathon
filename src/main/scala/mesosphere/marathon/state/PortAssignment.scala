package mesosphere.marathon
package state

/**
  * @param portName name of the port
  * @param effectiveIpAddress ip address on which the port can be reached (can be an agent's IP, an IP-per-Task
  *                           or None if its not known yet)
  * @param effectivePort resolved non-dynamic port. The task is reachable under effectiveIpAddress:effectivePort.
  * @param hostPort port requested on the Mesos Agent.
  * @param containerPort port on which the container is listening.
  */
case class PortAssignment(
  portName: Option[String],
  effectiveIpAddress: Option[String],
  effectivePort: Int,
  hostPort: Option[Int] = None,
  containerPort: Option[Int] = None)

object PortAssignment {
  /**
    * If you change this, please also update AppDefinition.json.
    */
  val PortNamePattern = """^[a-z0-9-]+$""".r
}
