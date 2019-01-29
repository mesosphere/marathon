package mesosphere.raml.backend.stringtemplate

import mesosphere.raml.ir.EnumT
import org.stringtemplate.v4.{ST, STGroup, STGroupDir, STGroupFile}

object EnumVisitor {

  def visit(enumT: EnumT): Seq[String] = {

    val templates: STGroup = new STGroupDir("templates")
    val enumTemplate: ST = templates.getInstanceOf("decl")
    enumTemplate.add("type", "int")
    enumTemplate.add("name", "x")
    enumTemplate.add("value", 0)
    Seq(enumTemplate.render())
  }
}
