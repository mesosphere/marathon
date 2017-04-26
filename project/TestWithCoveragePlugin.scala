import play.api.libs.json.Json
import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin
import scoverage.{Coverage, IOUtils, Serializer}
import scoverage.report.{CoberturaXmlWriter, ScoverageHtmlWriter, ScoverageXmlWriter}

// Adapted from SCovereageSbtPlugin so it can be run after test even if there are failures.
object TestWithCoveragePlugin extends AutoPlugin {
  override def requires: JvmPlugin.type = plugins.JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    val testWithCoverageReport: TaskKey[Unit] = taskKey[Unit]("Runs tests with coverage")
    val coverageDir: SettingKey[File] = settingKey[File]("Directory to output coverage into")

  }
  import autoImport._
  import scoverage.ScoverageSbtPlugin.autoImport._

  override lazy val projectSettings = Seq(
    coverageDir := target.value / "coverage",
    coverageDir in Test := target.value / "coverage",
    testWithCoverageReport := (testWithCoverageReport in Test).value,
    testWithCoverageReport in Test := runTestsWithCoverage(Test).value
  )

  def loadCoverage(target: File, log: Logger): Option[Coverage] = {
    val dataDir = target / "scala-2.11" / "scoverage-data"
    val coverageFile = Serializer.coverageFile(dataDir)
    log.info(s"Reading scoverage instumentation [$coverageFile]")
    if (coverageFile.exists) {
      val coverage = Serializer.deserialize(coverageFile)
      log.info(s"Reading scoverage measurements...")
      val measurementFiles = IOUtils.findMeasurementFiles(dataDir)
      val measurements = IOUtils.invoked(measurementFiles)
      coverage.apply(measurements)
      Some(coverage)
    } else {
      None
    }
  }

  case class HMTest(name: String, coverage: Map[String, String], result: String = "pass")
  implicit val hmtestFormat = Json.format[HMTest]

  /**
    * Convert test coverage data into the format that Phabricator/Harbormaster understands which is actually a
    * 'fake' unit test.
    *
    * {{{
    * {
    *    "name": "Test Coverage"
    *    "result": "pass"
    *    "coverage": {
    *       "file": "NNUCCC"
    *    }
    * }
    * }}}
    *
    * N = Not Executable
    * U = Not Covered
    * C = Covered
    */
  def writePhabricator(file: File, name: String, baseDir: File, coverage: Coverage): Unit = {
    val fileLineCoverage: Map[String, Map[Int, Int]] = coverage.statements.groupBy(_.source).map { case (sourceFile, statements) =>
      val lineCoverage = statements.groupBy(_.line).map { case (line, lineStatements) =>
        line -> lineStatements.map(_.count).sum
      }
      (new File(sourceFile)).getAbsolutePath.replaceAll(s"${baseDir.getAbsolutePath}/", "") -> lineCoverage
    }
    val phabCoverage: Map[String, String] = fileLineCoverage.map { case (file, lineData) =>
      file -> 1.to(lineData.keys.max).map { lineNo =>
        lineData.get(lineNo) match {
          case None => "N"
          case Some(x) if x < 1 => "U"
          case Some(x) => "C"
        }
      }.mkString("")
    }(collection.breakOut)

    IO.write(file, Json.stringify(Json.toJson(Seq(HMTest(s"$name Coverage", phabCoverage)))))
  }

  def writeCoverageReport(name: String, sourceDirs: Seq[File], coverage: Coverage, outputDir: File, target: File, baseDir: File, log: Logger): Unit = {
    log.info(s"Generating scoverage reports")
    outputDir.mkdirs()
    val coberturaDir = outputDir / "coverage-report"
    coberturaDir.mkdirs()
    val reportDir = outputDir / "scoverage-report"
    reportDir.mkdirs()

    val phabricatorFile = target / "phabricator-test-reports" / s"$name-coverage.json"

    log.info(s"Writing Cobertura report to ${coberturaDir / "cobertura.xml"}")
    new CoberturaXmlWriter(sourceDirs, coberturaDir).write(coverage)
    log.info(s"Writing XML coverage report ${reportDir / "scoverage.xml" }")
    new ScoverageXmlWriter(sourceDirs, reportDir, false).write(coverage)
    log.info(s"Writing HTML coverage report to ${reportDir / "index.html" }")
    new ScoverageHtmlWriter(sourceDirs, reportDir, None).write(coverage)
    log.info(s"Writing Phabricator coverage report to $phabricatorFile")
    writePhabricator(phabricatorFile, name, baseDir, coverage)
    log.info(s"Statement coverage.: ${coverage.statementCoverageFormatted}%")
    log.info(s"Branch coverage...: ${coverage.branchCoverageFormatted}%")
    log.info(s"Coverage reports completed")
  }

  def checkCoverage(coverage: Coverage, log: Logger, coverageMinimum: Double, failOnMinimum: Boolean): Unit = {
    val coveragePercent = coverage.statementCoveragePercent
    val coverageFormatted = coverage.statementCoverageFormatted
    if (coverageMinimum > 0) {
      def is100(d: Double) = Math.abs(100 - d) <= 0.00001

      if (is100(coverageMinimum) && is100(coveragePercent)) {
        log.info(s"100% Coverage !")
      } else if (coverageMinimum > coveragePercent) {
        log.error(s"Coverage is below minimum [$coverageFormatted% < $coverageMinimum%]")
        if (failOnMinimum) {
          throw new RuntimeException("Coverage minimum was not reached")
        }
      } else {
        log.info(s"Coverage is above minimum [$coverageFormatted% > $coverageMinimum%]")
      }
      log.info(s"All done. Coverage was [$coverageFormatted%]")
    }
  }

  def runTestsWithCoverage(config: Configuration, target: File, baseDir: File, sourceDirs: Seq[File], outputDir: File, log: Logger, coverageMinimum: Double, failOnMinimum: Boolean): Def.Initialize[Task[Unit]] = Def.task {
    (test in config).andFinally {
      loadCoverage(target, log).foreach { coverage =>
        writeCoverageReport(config.name, sourceDirs, coverage, outputDir, target, baseDir, log)
        checkCoverage(coverage, log, coverageMinimum, failOnMinimum)
      }
    }.value
  }

  def runTestsWithCoverage(config: Configuration): Def.Initialize[Task[Unit]] = Def.taskDyn {
    runTestsWithCoverage(config, target.value, baseDirectory.value, (sourceDirectories in Compile).value,
      (coverageDir in config).value, streams.value.log, (coverageMinimum in config).value,
      (coverageFailOnMinimum in config).value)
  }
}
