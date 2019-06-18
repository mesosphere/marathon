package mesosphere.raml.backend.treehugger

import mesosphere.raml.backend.{GeneratedFile, NoScalaFormat}
import treehugger.forest._
import treehuggerDSL._

case class GeneratedFileTreehugger(trees: Seq[Tree], jacksonSerializers: Seq[Tree] = Seq.empty) extends GeneratedFile {

  override def generateFile(pkg: String): String = {
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
