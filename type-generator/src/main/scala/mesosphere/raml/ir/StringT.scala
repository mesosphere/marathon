package mesosphere.raml.ir

import treehugger.forest.Tree

case class StringT(name: String, defaultValue: Option[String]) extends GeneratedClass {
  // TODO(karsten): This is actually part of the back end.
  override def toTree(): Seq[treehugger.forest.Tree] = Seq.empty[Tree]
}
