package mesosphere.raml.backend.stringtemplate

import java.util.Locale

import com.typesafe.scalalogging.StrictLogging
import mesosphere.raml.ir.EnumT
import org.fusesource.scalate.TemplateEngine

object EnumVisitor extends StrictLogging {

  def visit(enumT: EnumT): Seq[String] = {

    val EnumT(name, values, default, comments) = enumT

    val engine = new TemplateEngine
    val template = engine.load("templates/enum.ssp")
    val out = engine.layout("enum.ssp", template, Map("enum" -> enumT))

    Seq(out)
  }
}
