package mesosphere.marathon
package state

trait Container extends Product with Serializable {

  import Container.PortMapping

  def portMappings: Seq[PortMapping]
  val volumes: Seq[VolumeWithMount[Volume]]

  def hostPorts: Seq[Option[Int]] =
    portMappings.map(_.hostPort)

  def servicePorts: Seq[Int] =
    portMappings.map(_.servicePort)

  def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[VolumeWithMount[Volume]] = volumes): Container
}

object Container {

  /**
    * @param containerPort The container port to expose
    * @param hostPort      The host port to bind
    * @param servicePort   The well-known port for this service
    * @param protocol      Layer 4 protocol to expose (i.e. "tcp", "udp" or "udp,tcp" for both).
    * @param name          Name of the service hosted on this port.
    * @param labels        This can be used to decorate the message with metadata to be
    *                      interpreted by external applications such as firewalls.
    * @param networkNames  Specifies one or more container networks, by name, for which this PortMapping applies.
    */
  case class PortMapping(
      containerPort: Int = AppDefinition.RandomPortValue,
      hostPort: Option[Int] = None, // defaults to HostPortDefault for BRIDGE mode, None for USER mode
      servicePort: Int = AppDefinition.RandomPortValue,
      protocol: String = PortMapping.TCP,
      name: Option[String] = None,
      labels: Map[String, String] = Map.empty[String, String],
      networkNames: Seq[String] = Nil)
}