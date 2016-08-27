package mesosphere.raml

import sbt._
import sbt.Keys._

object RamlGeneratorPlugin extends AutoPlugin {
  object autoImport {
    lazy val ramlFiles = SettingKey[Seq[String]]("raml-files", "List of RAML 1.0 top level definitions togenerate from")
    lazy val ramlPackage = SettingKey[String]("raml-package", "Package to place all generated classes in")
    lazy val ramlGenerate = TaskKey[Unit]("raml-generate", "Generate the RAML files")
  }
  import autoImport._
  override lazy val projectSettings = inConfig(Compile)(Seq(
    ramlFiles in Compile := Seq(),
    ramlPackage in Compile := "mesosphere.marathon.raml",
    ramlGenerate in Compile := streams.map { s =>
      s.log("hello from the generator!")
      Nil
    }
  ))
}