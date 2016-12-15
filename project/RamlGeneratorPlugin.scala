package mesosphere.raml

import java.io.{FileOutputStream, ObjectOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

import sbt._
import sbt.Keys._
import org.raml.v2.api.RamlModelBuilder

import scala.collection.JavaConversions._
import scala.util.Try

object RamlGeneratorPlugin extends AutoPlugin {
  object autoImport {
    lazy val ramlFiles = settingKey[Seq[File]]("List of RAML 1.0 top level definitions togenerate from")
    lazy val ramlPackage = settingKey[String]("Package to place all generated classes in")
    lazy val ramlGenerate = taskKey[Seq[File]]("Generate the RAML files")
  }
  import autoImport._
  override lazy val projectSettings = inConfig(Compile)(Seq(
    ramlFiles := Seq(
      baseDirectory.value / "docs" / "docs" / "rest-api" / "public" / "api" / "api.raml"
    ),
    ramlPackage := "mesosphere.marathon.raml",
    ramlGenerate := {
      generate(ramlFiles.value, ramlPackage.value, sourceManaged.value, streams.value.log, streams.value.cacheDirectory)
    }
  ))

  private def storeTypeHashes(file: File, hashes: Map[String, String]): Unit = {
    IO.write(file, hashes.map { case (k, v) =>
      s"$k $v"
    }.mkString("\n"))
  }

  private def readTypeHashes(file: File): Map[String, String] = {
    Try(IO.readLines(file).view.map { line =>
      line.split(" ")
    }.withFilter(_.length == 2).map { a =>
      a(0) -> a(1)
    }.toMap).getOrElse(Map.empty[String, String])
  }

  def generate(ramlFiles: Seq[File], pkg: String, outputDir: File, log: Logger, cacheDir: File): Seq[File] = {
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
    val typesAsStr = types.mapValues(treehugger.forest.treeToString(_))
    val digest = MessageDigest.getInstance("SHA-256")
    val typeHashes = typesAsStr.mapValues(content =>
      Base64.getEncoder.encodeToString(digest.digest(content.getBytes(StandardCharsets.UTF_8))))

    val hashes = readTypeHashes(cacheDir / "type-cache")
    storeTypeHashes(cacheDir / "type-cache", typeHashes)
    typesAsStr.map { case (typeName, content) =>
      val file = outputDir / pkg.replaceAll("\\.", "/") / s"$typeName.scala"
      // don't write the file if it hasn't changed
      if (hashes.get(typeName).fold(true)(_ != typeHashes(typeName) || !file.exists())) {
        IO.write(file, content)
      }
      file
    }(collection.breakOut)
  }
}
