package mesosphere.raml.backend.treehugger

import mesosphere.raml.backend.NoScalaFormat
import treehugger.forest._
import treehuggerDSL._

case class GeneratedFile(objects: Seq[GeneratedObject]) {

  def generateFile(pkg: String): String = {
    val trees = objects.flatMap(o => o.trees)

    val rootBlock: Tree =
      if (trees.nonEmpty) {
          BLOCK(trees)
            .inPackage(pkg)
            .withComment(NoScalaFormat)
      } else {
          BLOCK()
            .withComment(s"Unsupported")
            .inPackage(pkg)
            .withComment(NoScalaFormat)
      }

      treehugger.forest.treeToString(rootBlock)
  }

}

case class GeneratedObject(name: String, trees: Seq[Tree], jacksonSerializer: Option[Symbol] = Option.empty) {

}