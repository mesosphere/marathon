package mesosphere.mesos

import org.apache.mesos.Protos.Attribute

trait Placed {
  def attributes: Seq[Attribute]
  def hostname: Option[String]
  def region: Option[String]
  def zone: Option[String]
}
