package mesosphere.raml

import sbt._
import sbt.Keys._
import org.raml.v2.api.RamlModelBuilder
import scala.collection.JavaConversions._

object RamlGeneratorPlugin extends AutoPlugin {
  object autoImport {
    lazy val ramlFiles = settingKey[Seq[File]]("List of RAML 1.0 top level definitions togenerate from")
    lazy val ramlPackage = settingKey[String]("Package to place all generated classes in")
    lazy val ramlGenerate = taskKey[Seq[File]]("Generate the RAML files")
  }
  import autoImport._
  override lazy val projectSettings = inConfig(Compile)(Seq(
    ramlFiles := Seq(
      baseDirectory.value / "docs" / "docs" / "rest-api" / "public" / "api" / "v2" / "pods.raml",
      baseDirectory.value / "docs" / "docs" / "rest-api" / "public" / "api" / "v2" / "queue.raml",
      baseDirectory.value / "docs" / "docs" / "rest-api" / "public" / "api" / "v2" / "apps.raml",
      baseDirectory.value / "docs" / "docs" / "rest-api" / "public" / "api" / "v2" / "groups.raml"
    ),
    ramlPackage := "mesosphere.marathon.raml",
    ramlGenerate := {
      generate(ramlFiles.value, ramlPackage.value, sourceManaged.value, streams.value.log)
    }
  ))

  def generate(ramlFiles: Seq[File], pkg: String, outputDir: File, log: Logger): Seq[File] = {
    val models = ramlFiles.map { file =>
      val model = new RamlModelBuilder().buildApi(file)
      if (model.hasErrors) {
        model.getValidationResults.foreach { error =>
          sys.error(error.toString)
        }
      }
      model
    }

    val types = RamlTypeGenerator(models.toVector, pkg)
    types.map { case (typeName, content) =>
      val file = outputDir / pkg.replaceAll("\\.", "/") / s"$typeName.scala"
      IO.write(file, treehugger.forest.treeToString(content))
      file
    }(collection.breakOut)
  }
}