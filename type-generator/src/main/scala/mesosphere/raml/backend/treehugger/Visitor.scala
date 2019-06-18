package mesosphere.raml.backend.treehugger

import mesosphere.raml.ir._
import treehugger.forest._

object Visitor {

  def visit(generated: Seq[GeneratedClass]): GeneratedFileTreehugger = {
      generated
        .map(visit)
        .foldLeft(GeneratedFileTreehugger(Seq.empty)) { (acc, next) => GeneratedFileTreehugger(acc.objects ++ next.objects)}
  }

  def visit(generated: GeneratedClass): GeneratedFileTreehugger = {
    generated match {
      case enumT: EnumT => EnumVisitor.visit(enumT)
      case objectT: ObjectT => ObjectVisitor.visit(objectT)
      case stringT: StringT => StringVisitor.visit(stringT)
      case unionT: UnionT => UnionVisitor.visit(unionT)
    }
  }

}
