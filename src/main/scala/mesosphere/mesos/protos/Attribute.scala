package mesosphere.mesos.protos

trait Attribute

case class TextAttribute(name: String, text: String) extends Attribute
