import sbt.Keys._
import sbt._

/**
  * Checks that files use double packages.
  */
object BasicLintingPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def trigger = allRequirements

  object autoImport {
    val basicLintCheck = taskKey[Unit]("Checks for basic linting issues")
    val doublePackageRoot = settingKey[String]("Root double package, packages that start with this package require a double declaration")
  }

  import autoImport._

  override lazy val projectSettings = Seq(
    compile in Compile := (compile in Compile).dependsOn(basicLintCheck in Compile).value,
    doublePackageRoot := "mesosphere.marathon",
    basicLintCheck in Compile := checkBasicLinting(streams.value.log, doublePackageRoot.value, (sourceDirectories in Compile).value.filter(_ != (sourceManaged in Compile).value)),
    basicLintCheck in Test := checkBasicLinting(streams.value.log, doublePackageRoot.value, (sourceDirectories in Test).value.filter(_ != (sourceManaged in Test).value)),
    compile in Test := (compile in Test).dependsOn(basicLintCheck in Test).value
  )

  def checkBasicLinting(logger: Logger, doublePackageRoot: String, sourceDirectories: Seq[File]): Unit = {
    var packageError = false
    var otherLintError = false

    sourceDirectories.descendantsExcept("*.scala", NothingFilter).get.foreach { file =>
      var lineNumber = 0
      var pkgFound = false
      var otherError = false
      IO.readLines(file).foreach { line =>
        lineNumber += 1
        if (line.startsWith("package") && !pkgFound) {
          pkgFound = true
          if (line.startsWith(s"package $doublePackageRoot.")) {
            val pkg = line.replaceAll("package ", "")
            logger.error(
              s"""$file:$lineNumber does not use double package notation. (is: $pkg) e.g.:
                 |package $doublePackageRoot
                 |package ${pkg.replaceAll(s"package $doublePackageRoot.", "")}
              """.stripMargin)
            packageError = true
          }
        }

        if (file.getName != "ExecutionContexts.scala" && (line.contains("ExecutionContext.global") || line.contains("ExecutionContext.Implicits"))) {
          logger.error(
            s"$file:$lineNumber uses scala's global execution context. Please use mesosphere.marathon.core.async.ExecutionContexts.*"
          )
          otherLintError = true
        }

      }
    }
    if (packageError) {
      sys.error("One or more files didn't use proper double package notation.")
    }
    if (otherLintError) {
      sys.error("Other lint errors found.")
    }
  }

}