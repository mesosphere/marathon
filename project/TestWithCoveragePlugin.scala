import java.time.{ ZonedDateTime, ZoneId }
import java.time.format.DateTimeFormatter
import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin
import scala.util.Properties
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

  /**
   * Writes out a CSV with coverage data for each class.
   *
   * The header of the CSV is
   * project_name,pipeline_name,build_id,build_timestamp,target_name,package_name,class_name,class_file_name,statements_count,statements_invoked,branches_count,branches_invoked
   *
   *
   * @param file CSV target
   * @param name Target name, e.g. test-unit
   * @param baseDir Bas directory of project
   * @param coverage Coverage for build target.
   */
  def writeCsv(file: File, name: String, baseDir: File, coverage: Coverage): Unit = {
    val csvHeader = "project_name,pipeline_name,build_id,build_timestamp,target_name,package_name,class_name,class_file_name,statements_count,statements_invoked,branches_count,branches_invoked"

    val projectName = Properties.envOrElse("JOB_NAME", "no_project_name_defined")
    val pipelineName = Properties.envOrElse("BRANCH_NAME", "no_pipeline_name_defined")
    val buildId = Properties.envOrElse("BUILD_ID", "no_build_id_defined")
    val dateTimeFormatter = DateTimeFormatter.ofPattern("Y-MM-d_H:m:s")
    val buildTimestamp = ZonedDateTime.now(ZoneId.of("UTC")).format(dateTimeFormatter)

    val buildDetails = s"$projectName, $pipelineName, $buildId, $buildTimestamp, $name"
    val csv: Seq[String] = csvHeader +: coverage.packages.view.flatMap { p =>

      p.classes.map { c =>

        val classSourceFile = c.source.replaceAll(s"${baseDir.getAbsolutePath}/", "")

        s"$buildDetails, ${p.name}, ${c.fullClassName}, ${classSourceFile}, ${c.statementCount}, ${c.invokedStatementCount}, ${c.branchCount}, ${c.invokedBranchesCount}"
      }
    }

    IO.write(file, csv.mkString("\n"))
  }

  def writeCoverageReport(name: String, sourceDirs: Seq[File], coverage: Coverage, outputDir: File, target: File, baseDir: File, log: Logger): Unit = {
    log.info(s"Generating scoverage reports")
    outputDir.mkdirs()
    val coberturaDir = outputDir / "coverage-report"
    coberturaDir.mkdirs()
    val reportDir = outputDir / "scoverage-report"
    reportDir.mkdirs()

    log.info(s"Writing Cobertura report to ${coberturaDir / "cobertura.xml"}")
    new CoberturaXmlWriter(sourceDirs, coberturaDir).write(coverage)

    log.info(s"Writing XML coverage report ${reportDir / "scoverage.xml" }")
    new ScoverageXmlWriter(sourceDirs, reportDir, false).write(coverage)

    log.info(s"Writing HTML coverage report to ${reportDir / "index.html" }")
    new ScoverageHtmlWriter(sourceDirs, reportDir, None).write(coverage)

    log.info(s"Writing CSV coverage report to ${reportDir / "scoverage.csv" }")
    writeCsv(reportDir / "scoverage.csv", name, baseDir, coverage)

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
