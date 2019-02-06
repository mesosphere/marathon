package mesosphere.raml.backend.liquid

import mesosphere.raml.ir.FieldT

object FieldVisitor {

  def visit(params: Seq[FieldT]): Seq[String] = {
    params.map { param => s"${param.name}: ${param.`type`}" }
  }
}
