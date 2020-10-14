package mesosphere.raml.ir

case class UnionT(name: String, childTypes: Seq[GeneratedClass], comments: Seq[String]) extends GeneratedClass {
  override def toString: String = s"Union($name, $childTypes)"
}
