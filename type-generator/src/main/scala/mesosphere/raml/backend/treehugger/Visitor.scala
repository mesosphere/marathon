package mesosphere.raml.backend.treehugger

import mesosphere.raml.ir._
import treehugger.forest._

object Visitor {

  def visit(generated: Seq[GeneratedClass]): Seq[Tree] = generated.flatMap(visit)

  def visit(generated: GeneratedClass): Seq[Tree] = {
    generated match {
      case enumT: EnumT => EnumVisitor.visit(enumT)
      case objectT: ObjectT => ObjectVisitor.visit(objectT)
      case stringT: StringT => StringVisitor.visit(stringT)
      case unionT: UnionT => UnionVisitor.visit(unionT)
    }
  }

}
