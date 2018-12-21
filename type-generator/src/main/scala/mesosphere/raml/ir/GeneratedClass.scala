package mesosphere.raml.ir

import treehugger.forest.Tree

trait GeneratedClass {
  val name: String

  // TODO(karsten): This is actually part of the back end.
  def toTree(): Seq[Tree]
}
