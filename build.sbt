import com.amazonaws.auth.InstanceProfileCredentialsProvider
import ohnosequences.sbt.SbtS3Resolver
import org.scalastyle.sbt.ScalastylePlugin.{buildSettings => styleSettings}
import sbtassembly.Plugin.AssemblyKeys._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.ReleaseStep
import spray.revolver.RevolverPlugin.Revolver.{settings => revolverSettings}

import scalariform.formatter.preferences._

lazy val pluginInterface: Project = Project("plugin-interface", file("plugin-interface")).settings(
  baseSettings ++ asmSettings ++ formatSettings ++ scalaStyleSettings ++ publishSettings ++
    Seq(libraryDependencies ++= Dependencies.pluginInterface)
)

lazy val root: Project = Project("marathon", file(".")).settings(
  baseSettings ++ buildInfoSettings ++ asmSettings ++ customReleaseSettings ++ formatSettings ++ scalaStyleSettings ++
    revolverSettings ++ testSettings ++ integrationTestSettings ++ teamCitySetEnvSettings ++
    Seq(
      unmanagedResourceDirectories in Compile += file("docs/docs/rest-api"),
      libraryDependencies ++= Dependencies.root,
      parallelExecution in Test := false,
      sourceGenerators in Compile <+= buildInfo,
      buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion),
      buildInfoPackage := "mesosphere.marathon",
      fork in Test := true
    )
).configs(IntegrationTest)
  .dependsOn(pluginInterface)
  // run mesos-simulation/test:test when running test
  .settings((test in Test) <<= (test in Test) dependsOn (test in Test in LocalProject("mesos-simulation")))

lazy val mesosSimulation: Project = Project("mesos-simulation", file("mesos-simulation")).settings(
  baseSettings ++ formatSettings ++ scalaStyleSettings ++ revolverSettings ++ testSettings ++ integrationTestSettings
).dependsOn(root % "compile->compile; test->test").configs(IntegrationTest)

/**
 * Determine scala test runner output. `-e` for reporting on standard error.
 *
 * W - without color
 * D - show all durations
 * S - show short stack traces
 * F - show full stack traces
 * U - unformatted mode
 * I - show reminder of failed and canceled tests without stack traces
 * T - show reminder of failed and canceled tests with short stack traces
 * G - show reminder of failed and canceled tests with full stack traces
 * K - exclude TestCanceled events from reminder
 *
 * http://scalatest.org/user_guide/using_the_runner
 */
lazy val formattingTestArg = Tests.Argument("-eDFG")

lazy val integrationTestSettings = inConfig(IntegrationTest)(Defaults.testTasks) ++
  Seq(testOptions in IntegrationTest := Seq(formattingTestArg, Tests.Argument("-n", "integration")))

lazy val testSettings = Seq(
  testOptions in Test := Seq(formattingTestArg, Tests.Argument("-l", "integration")),
  parallelExecution in Test := false,
  fork in Test := true
)

lazy val testScalaStyle = taskKey[Unit]("testScalaStyle")

lazy val scalaStyleSettings = styleSettings ++ Seq(
  testScalaStyle := {
    org.scalastyle.sbt.ScalastylePlugin.scalastyle.in(Compile).toTask("").value
  },
  (test in Test) <<= (test in Test) dependsOn testScalaStyle
)

lazy val IntegrationTest = config("integration") extend Test

lazy val baseSettings = Seq(
  organization := "mesosphere.marathon",
  scalaVersion := "2.11.7",
  crossScalaVersions := Seq(scalaVersion.value),
  scalacOptions in Compile ++= Seq(
    "-encoding", "UTF-8",
    "-target:jvm-1.8",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xlog-reflective-calls",
    "-Xlint",
    "-Ywarn-unused-import",
    "-Xfatal-warnings",
    "-Yno-adapted-args",
    "-Ywarn-numeric-widen"
  ),
  javacOptions in Compile ++= Seq(
    "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation"
  ),
  resolvers ++= Seq(
    "Mesosphere Public Repo" at "http://downloads.mesosphere.com/maven",
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/",
    "Spray Maven Repository" at "http://repo.spray.io/"
  ),
  fork in Test := true
)

lazy val asmSettings = assemblySettings ++ Seq(
  mergeStrategy in assembly <<= (mergeStrategy in assembly) { old => {
    case "application.conf" => MergeStrategy.concat
    case "META-INF/jersey-module-version" => MergeStrategy.first
    case "org/apache/hadoop/yarn/util/package-info.class" => MergeStrategy.first
    case "org/apache/hadoop/yarn/factories/package-info.class" => MergeStrategy.first
    case "org/apache/hadoop/yarn/factory/providers/package-info.class" => MergeStrategy.first
    case x => old(x)
  }
  },
  excludedJars in assembly <<= (fullClasspath in assembly) map { cp =>
    val exclude = Set(
      "commons-beanutils-1.7.0.jar",
      "stax-api-1.0.1.jar",
      "commons-beanutils-core-1.8.0.jar",
      "servlet-api-2.5.jar",
      "jsp-api-2.1.jar"
    )
    cp filter { x => exclude(x.data.getName) }
  }
)

lazy val formatSettings = scalariformSettings ++ Seq(
  ScalariformKeys.preferences := FormattingPreferences()
    .setPreference(IndentWithTabs, false)
    .setPreference(IndentSpaces, 2)
    .setPreference(AlignParameters, true)
    .setPreference(DoubleIndentClassDeclaration, true)
    .setPreference(MultilineScaladocCommentsStartOnFirstLine, false)
    .setPreference(PlaceScaladocAsterisksBeneathSecondAsterisk, true)
    .setPreference(PreserveDanglingCloseParenthesis, true)
    .setPreference(CompactControlReadability, true) //MV: should be false!
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(PreserveSpaceBeforeArguments, true)
    .setPreference(SpaceBeforeColon, false)
    .setPreference(SpaceInsideBrackets, false)
    .setPreference(SpaceInsideParentheses, false)
    .setPreference(SpacesWithinPatternBinders, true)
    .setPreference(FormatXml, true)
)

/**
 * This is the standard release process without
 * -publishArtifacts
 * -setNextVersion
 * -commitNextVersion
 */
lazy val customReleaseSettings = releaseSettings ++ Seq(
  ReleaseKeys.releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    pushChanges
  ))

/**
 * This on load trigger is used to set parameters in teamcity.
 * It is only executed within teamcity and can be ignored otherwise.
 * It will set values as build and env parameter.
 * Those parameters can be used in subsequent build steps and dependent builds.
 * TeamCity does this by watching the output of the build it currently performs.
 * See: https://confluence.jetbrains.com/display/TCD8/Build+Script+Interaction+with+TeamCity
 */
lazy val teamCitySetEnvSettings = Seq(
  onLoad in Global := {
    sys.env.get("TEAMCITY_VERSION") match {
      case None => // no-op
      case Some(teamcityVersion) =>
        def reportParameter(key: String, value: String): Unit = {
          //env parameters will be made available as environment variables
          println(s"##teamcity[setParameter name='env.SBT_$key' value='$value']")
          //system parameters will be made available as teamcity build parameters
          println(s"##teamcity[setParameter name='system.sbt.$key' value='$value']")
        }
        reportParameter("SCALA_VERSION", scalaVersion.value)
        reportParameter("PROJECT_VERSION", version.value)
    }
    (onLoad in Global).value
  }
)

lazy val publishSettings = S3Resolver.defaults ++ Seq(
  publishTo := Some(s3resolver.value("Mesosphere Public Repo (S3)", s3("downloads.mesosphere.io/maven"))),
  SbtS3Resolver.s3credentials := new InstanceProfileCredentialsProvider()
)