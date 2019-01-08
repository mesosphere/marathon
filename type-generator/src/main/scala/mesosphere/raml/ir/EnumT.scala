package mesosphere.raml.ir

case class EnumT(name: String, values: Set[String], default: Option[String], comments: Seq[String]) extends GeneratedClass {
  val sortedValues = values.toVector.sorted
  override def toString: String = s"Enum($name, $values)"
}
