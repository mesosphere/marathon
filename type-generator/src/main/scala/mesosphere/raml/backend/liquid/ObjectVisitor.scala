package mesosphere.raml.backend.liquid

import mesosphere.raml.ir.ObjectT
import scala.collection.JavaConverters._

object ObjectVisitor {

  def visit(objectT: ObjectT): Seq[String] = {
    val ObjectT(name, fields, parentType, comments, childTypes, discriminator, discriminatorValue, serializeOnly) = objectT

    val actualFields = fields.filter(_.rawName != discriminator.getOrElse(""))
    val params = FieldVisitor.visit(actualFields).asJava
    val out: String = Template("object.liquid").render("name", name, "params", params)
    Seq(out)
  }
}
