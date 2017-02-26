import sbt.Keys._
import sbt._

/**
  * Checks that files use double packages.
  */
object DoublePackagePlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def trigger = allRequirements

  object autoImport {
    val doublePackageCheck = taskKey[Unit]("Checks for double package declarations")
    val doublePackageRoot = settingKey[String]("Root double package, packages that start with this package require a double declaration")
  }

  import autoImport._

  override lazy val projectSettings = Seq(
    compile in Compile := (compile in Compile).dependsOn(doublePackageCheck in Compile).value,
    doublePackageRoot := "mesosphere.marathon",
    doublePackageCheck in Compile := checkDoublePackage(streams.value.log, doublePackageRoot.value, (sourceDirectories in Compile).value.filter(_ != (sourceManaged in Compile).value)),
    doublePackageCheck in Test := checkDoublePackage(streams.value.log, doublePackageRoot.value, (sourceDirectories in Test).value.filter(_ != (sourceManaged in Test).value)),
    compile in Test := (compile in Test).dependsOn(doublePackageCheck in Test).value
  )

  def checkDoublePackage(logger: Logger, doublePackageRoot: String, sourceDirectories: Seq[File]): Unit = {
    var error = false
    sourceDirectories.descendantsExcept("*.scala", NothingFilter).get.foreach { file =>
      IO.reader(file) { reader =>
        var pkgFound = false
        var lineNumber = 0
        while (!pkgFound) {
          val line = Option(reader.readLine())
          line.fold {
            pkgFound = true
          } { pkg =>
            lineNumber += 1

            if (pkg.startsWith("package")) {
              pkgFound = true
            }
            if (pkg.startsWith(s"package $doublePackageRoot.")) {
              logger.error(
                s"""$file:$lineNumber does not use double package notation. (is: $pkg) e.g.:
                   |package $doublePackageRoot
                   |package ${pkg.replaceAll(s"package $doublePackageRoot.", "")}
              """.stripMargin)
              error = true
            }
          }
        }
      }
    }
    if (error) {
      sys.error("One or more files didn't use proper double package notation.")
    }
  }

}