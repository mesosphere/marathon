package mesosphere.raml.backend.treehugger

import mesosphere.raml.ir._
import treehugger.forest._

object Visitor {

  def visit(generated: Seq[GeneratedClass]): GeneratedFile = {
    generated
      .map(visit)
      .foldLeft(GeneratedFile(Seq.empty)) { (acc, next) => GeneratedFile(acc.objects ++ next.objects) }
  }

  def visit(generated: GeneratedClass): GeneratedFile = {
    generated match {
      case enumT: EnumT => EnumVisitor.visit(enumT)
      case objectT: ObjectT => ObjectVisitor.visit(objectT)
      case stringT: StringT => StringVisitor.visit(stringT)
      case unionT: UnionT => UnionVisitor.visit(unionT)
    }
  }

}
