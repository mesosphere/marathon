package mesosphere.raml.backend.treehugger

import mesosphere.raml.ir.StringT

object StringVisitor {

  def visit(s: StringT): GeneratedFile = GeneratedFile(Seq.empty)
}
