package mesosphere.raml.backend.treehugger

import mesosphere.raml.backend.NoScalaFormat
import treehugger.forest._
import treehuggerDSL._

/**
  * Represents a file that is generated. Contains a list of generated objects (classes, case classes, traits, etc.)
  *
  * @param objects The code objects in this file
  */
case class GeneratedFile(objects: Seq[GeneratedObject]) {

  /**
    * Converts the internal treehugger representation into a rendered string
    *
    * @param pkg The root package where the file should be generated
    * @return A string that represents the file content
    */
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

/**
  * Represents a set of related top-level objects inside a file, for example for one RAML type we may generate
  * a case class, a companion object and a jackson serializer singleton
  *
  * @param name The name of the object for which the top-level were generated
  * @param trees A list of top-level objects related to the name
  * @param jacksonSerializer An optional jackson serializer
  */
case class GeneratedObject(name: String, trees: Seq[Tree], jacksonSerializer: Option[Symbol] = Option.empty)
