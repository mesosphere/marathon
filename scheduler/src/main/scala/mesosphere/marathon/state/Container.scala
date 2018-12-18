package mesosphere.marathon
package state

trait Container extends Product with Serializable {

  import Container.{Docker, PortMapping}

  def portMappings: Seq[PortMapping]
  val volumes: Seq[VolumeWithMount[Volume]]

  // TODO(nfnt): Remove this field and use type matching instead.
  def docker: Option[Docker] = {
    this match {
      case docker: Docker => Some(docker)
      case _ => None
    }
  }

  def hostPorts: Seq[Option[Int]] =
    portMappings.map(_.hostPort)

  def servicePorts: Seq[Int] =
    portMappings.map(_.servicePort)

  def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[VolumeWithMount[Volume]] = volumes): Container
}
