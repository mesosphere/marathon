package mesosphere.raml.ir

import scala.beans.BeanProperty
import scala.collection.JavaConverters._

case class EnumT(@BeanProperty name: String, values: Set[String], default: Option[String], comments: Seq[String]) extends GeneratedClass {
  val sortedValues = values.toSeq.sorted
  def getSortedValues = values.toSeq.sorted.asJava
  override def toString: String = s"Enum($name, $values)"
}
