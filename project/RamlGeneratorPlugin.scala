package mesosphere.raml

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

import sbt._
import sbt.Keys._
import org.raml.v2.api.RamlModelBuilder
import org.yaml.snakeyaml.Yaml

import scala.collection.JavaConversions._
import scala.util.Try
import scala.annotation.tailrec

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

  private val IncludeDirective = ".*!include ([^ ]+).*".r
  /** Given a list of input files, crawls them by looking for include directives. Only crawls files once.
    * Poor mans because it uses regex to parse the !include directives (snakeyaml dies on these)
    * Uses regex because snakeyaml dies on !include and RamlModelBuilder does not expose the necessary metadata
    * And then we parse the yaml and look for a `uses` key because this is the second way to include files with raml
    */
  @tailrec def poorMansIncludeCrawler(log: Logger, files: List[File], discovered: Set[File]): Set[File] = files match {
    case Nil =>
      discovered
    case head :: rest =>
      val folder = head.getParentFile
      val contents = IO.read(head)
      val includes = contents.split("\n").iterator
        .collect { case IncludeDirective(fileName) => fileName }
        .toSeq

      val yaml = (new Yaml()).loadAs(contents.replaceAll("!include", "include"), classOf[java.util.Map[_, _]])
      val uses = Option(yaml.get("uses"))
        .collect { case m: java.util.Map[_, _] =>
          m.values().iterator.collect { case fileName: String => fileName }.toSeq
        }
        .getOrElse(Nil)

      val references = (uses ++ includes).map { fileName =>
        new File(folder, fileName).getCanonicalFile
      }.filterNot(discovered).toSet

      val newThingsToCrawl = references.
        filter { _.getName.split('.').lastOption.exists(_ == "raml") }.
        filter { file =>
          (file.isFile) || {
            log.warn(s"File ${file} does not exist (referenced by ${head})")
            false
          }
        }
      poorMansIncludeCrawler(log, rest ++ newThingsToCrawl, discovered ++ references)
  }

  /**
    * Generates RAML scala sources for the provided input raml files.
    *
    * First, we crawl all of the input files for directives. Then, we use this crawled result as an input to the
    * cache function and only re-run if any of these files change.
    *
    * @param ramlFiles the input files from which to start; referenced include files are also processed
    * @param pkg the name of the package the generated Scala sources should use
    * @param log sbt logger
    * @param cacheDir the CacheDir for this build step
    */
  def generate(ramlFiles: Seq[File], pkg: String, outputDir: File, log: Logger, cacheDir: File): Seq[File] = {
    val start = System.currentTimeMillis()

    log.debug("Discovering RAML input files")
    val allInputFiles = poorMansIncludeCrawler(log, ramlFiles.toList, Set.empty)

    val cachedCompile = FileFunction.cached(
      cacheDir,
      inStyle = FilesInfo.lastModified,
      outStyle = FilesInfo.exists) { (_: Set[File]) =>
      log.info("Constructing RAML model")
      val models = ramlFiles.map { file =>
        val model = new RamlModelBuilder().buildApi(file)
        if (model.hasErrors) {
          model.getValidationResults.foreach { error =>
            sys.error(error.toString)
          }
        }
        model
      }

      log.info("Generating RAML scala sources")
      val types = RamlTypeGenerator(models.toVector, pkg)
      val typesAsStr = types.mapValues(treehugger.forest.treeToString(_))
      val digest = MessageDigest.getInstance("SHA-256")
      val typeHashes = typesAsStr.mapValues(content =>
        Base64.getEncoder.encodeToString(digest.digest(content.getBytes(StandardCharsets.UTF_8))))

      val hashes = readTypeHashes(cacheDir / "type-cache")
      storeTypeHashes(cacheDir / "type-cache", typeHashes)
      val results: Set[File] = typesAsStr.map { case (typeName, content) =>
        val file = outputDir / pkg.replaceAll("\\.", "/") / s"$typeName.scala"
        // don't write the file if it hasn't changed
        if (hashes.get(typeName).fold(true)(_ != typeHashes(typeName) || !file.exists())) {
          IO.write(file, content)
        }
        file
      }(collection.breakOut)
      log.info(s"Done generating ${results.size} RAML Scala sources; took ${(System.currentTimeMillis() - start) / 1000} seconds total")
      results
    }
    cachedCompile(allInputFiles).toSeq
  }
}
