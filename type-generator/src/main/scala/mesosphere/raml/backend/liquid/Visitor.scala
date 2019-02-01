package mesosphere.raml.backend.liquid

import mesosphere.raml.ir._

object Visitor {

  def visit(generated: Seq[GeneratedClass]): Seq[String] = generated.flatMap(visit)

  def visit(generated: GeneratedClass): Seq[String] = {
    generated match {
      case enumT: EnumT => EnumVisitor.visit(enumT)
      case objectT: ObjectT => ???
      case stringT: StringT => ???
      case unionT: UnionT => ???
    }
  }
}
