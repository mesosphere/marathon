package mesosphere.raml.backend.liquid

import com.typesafe.scalalogging.StrictLogging
import mesosphere.raml.ir.EnumT

object EnumVisitor extends StrictLogging {

  def visit(enumT: EnumT): Seq[String] = {
    val out : String = Template("enum.liquid").render(true, "enum", enumT)
    Seq(out)
  }
}
