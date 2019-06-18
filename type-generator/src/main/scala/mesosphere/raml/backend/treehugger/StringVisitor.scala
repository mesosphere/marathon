package mesosphere.raml.backend.treehugger

import mesosphere.raml.ir.StringT

import treehugger.forest.Tree

object StringVisitor {

  def visit(s: StringT): GeneratedFileTreehugger =  GeneratedFileTreehugger(Seq.empty[Tree])
}
