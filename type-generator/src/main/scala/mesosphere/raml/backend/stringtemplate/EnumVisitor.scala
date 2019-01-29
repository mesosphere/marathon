package mesosphere.raml.backend.stringtemplate

import com.typesafe.scalalogging.StrictLogging
import mesosphere.raml.ir.EnumT
import org.stringtemplate.v4.{ST, STGroup, STGroupFile}

object EnumVisitor extends StrictLogging {

  def visit(enumT: EnumT): Seq[String] = {

    val EnumT(name, values, default, comments) = enumT

    val templates: STGroup = new STGroupFile("templates/enum.stg")
    logger.info(templates.show())

    val enumTemplate: ST = templates.getInstanceOf("base")
    enumTemplate.add("name", name)

    Seq(enumTemplate.render())
  }
}
