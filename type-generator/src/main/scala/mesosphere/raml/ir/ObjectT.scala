package mesosphere.raml.ir

case class ObjectT(
    name: String,
    fields: Seq[FieldT],
    parentType: Option[String],
    comments: Seq[String],
    childTypes: Seq[ObjectT] = Nil,
    discriminator: Option[String] = None,
    discriminatorValue: Option[String] = None,
    serializeOnly: Boolean = false,
    parentObject: Option[ObjectT] = None
) extends GeneratedClass {
  override def toString: String =
    parentType.fold(s"$name(${fields.mkString(", ")})")(parent => s"$name(${fields.mkString(" , ")}) extends $parent")
}
