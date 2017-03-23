import com.amazonaws.auth.{EnvironmentVariableCredentialsProvider, InstanceProfileCredentialsProvider}
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.packager.docker.ExecCmd
import mesosphere.raml.RamlGeneratorPlugin
import sbt.Tests.SubProcess

import scalariform.formatter.preferences.{AlignArguments, AlignParameters, AlignSingleLineCaseStatements, CompactControlReadability, DanglingCloseParenthesis, DoubleIndentClassDeclaration, FormatXml, FormattingPreferences, IndentSpaces, IndentWithTabs, MultilineScaladocCommentsStartOnFirstLine, PlaceScaladocAsterisksBeneathSecondAsterisk, Preserve, PreserveSpaceBeforeArguments, SpaceBeforeColon, SpaceInsideBrackets, SpaceInsideParentheses, SpacesAroundMultiImports, SpacesWithinPatternBinders}

lazy val SerialIntegrationTest = config("serial-integration") extend Test
lazy val IntegrationTest = config("integration") extend Test
lazy val UnstableTest = config("unstable") extend Test
lazy val UnstableIntegrationTest = config("unstable-integration") extend Test

def formattingTestArg(target: File) = Tests.Argument("-u", target.getAbsolutePath, "-eDFG")

resolvers += Resolver.sonatypeRepo("snapshots")
addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % "0.1.17")


lazy val checkDoublePackage = taskKey[Unit]("Checks all scala sources use a double declaration")

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

lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
  ScalariformKeys.preferences := FormattingPreferences()
    .setPreference(AlignArguments, false)
    .setPreference(AlignParameters, false)
    .setPreference(AlignSingleLineCaseStatements, false)
    .setPreference(CompactControlReadability, false)
    .setPreference(DoubleIndentClassDeclaration, true)
    .setPreference(DanglingCloseParenthesis, Preserve)
    .setPreference(FormatXml, true)
    .setPreference(IndentSpaces, 2)
    .setPreference(IndentWithTabs, false)
    .setPreference(MultilineScaladocCommentsStartOnFirstLine, false)
    .setPreference(PlaceScaladocAsterisksBeneathSecondAsterisk, true)
    .setPreference(PreserveSpaceBeforeArguments, true)
    .setPreference(SpacesAroundMultiImports, true)
    .setPreference(SpaceBeforeColon, false)
    .setPreference(SpaceInsideBrackets, false)
    .setPreference(SpaceInsideParentheses, false)
    .setPreference(SpacesWithinPatternBinders, true)
)

lazy val commonSettings = inConfig(SerialIntegrationTest)(Defaults.testTasks) ++
  inConfig(IntegrationTest)(Defaults.testTasks) ++
  inConfig(UnstableTest)(Defaults.testTasks) ++
  inConfig(UnstableIntegrationTest)(Defaults.testTasks) ++
  aspectjSettings ++ Seq(
  autoCompilerPlugins := true,
  organization := "mesosphere.marathon",
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq(scalaVersion.value),
  scalacOptions in Compile ++= Seq(
    "-encoding", "UTF-8",
    "-target:jvm-1.8",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xfuture",
    "-Xlog-reflective-calls",
    "-Xlint",
    "-Xfatal-warnings",
    "-Yno-adapted-args",
    "-Ywarn-numeric-widen",
    //"-Ywarn-dead-code", We should turn this one on soon
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    //"-Ywarn-unused", We should turn this one on soon
    "-Ywarn-unused-import",
    //"-Ywarn-value-discard", We should turn this one on soon.
    "-Yclosure-elim",
    "-Ydead-code"
  ),
  scalacOptions in Test ~= { _.filter(co => !(co.startsWith("-Xplugin") || co.startsWith("-P"))) },
  javacOptions in Compile ++= Seq(
    "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation"
  ),
  resolvers ++= Seq(
    "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/",
    "Apache Shapshots" at "https://repository.apache.org/content/repositories/snapshots/",
    "Mesosphere Public Repo" at "https://downloads.mesosphere.com/maven"
  ),
  cancelable in Global := true,

  checkDoublePackage := {
    ((sources in Compile).value ++ (sources in Test).value).withFilter(_.toPath.endsWith(".scala")).foreach { file =>
      IO.reader(file) { reader =>
        println(s"Checking $file")
        var pkgFound = false
        while(!pkgFound) {
          val line = Option(reader.readLine())
          line.fold {
            pkgFound = true
          } { pkg =>
            if (pkg.startsWith("package")) {
              pkgFound = true
            }
            if (pkg.startsWith("package mesosphere.marathon") && pkg.trim().length > "package mesosphere.marathon".length) {
              sys.error(s"""$file does not use double package notation. e.g.:
                           |package mesosphere.marathon
                           |package ${pkg.replaceAll("package mesosphere.marathon.", "")}
              """.stripMargin)
            }
          }
        }
      }
    }
  },
  compile in Compile := {
    checkDoublePackage.value
    (compile in Compile).value
  },
  publishTo := Some(s3resolver.value(
    "Mesosphere Public Repo (S3)",
    s3("downloads.mesosphere.io/maven")
  )),
  s3credentials := new EnvironmentVariableCredentialsProvider() | new InstanceProfileCredentialsProvider(),

  testListeners := Seq(new PhabricatorTestReportListener(target.value / "phabricator-test-reports")),
  parallelExecution in Test := true,
  testForkedParallel in Test := true,
  testOptions in Test := Seq(formattingTestArg(target.value / "test-reports"),
    Tests.Argument("-l", "mesosphere.marathon.IntegrationTest",
      "-l", "mesosphere.marathon.SerialIntegrationTest",
      "-l", "mesosphere.marathon.UnstableTest",
      "-y", "org.scalatest.WordSpec")),
  fork in Test := true,

  parallelExecution in UnstableTest := true,
  testForkedParallel in UnstableTest := true,
  testOptions in UnstableTest := Seq(formattingTestArg(target.value / "test-reports" / "unstable"), Tests.Argument(
    "-l", "mesosphere.marathon.IntegrationTest",
    "-l", "mesosphere.marathon.SerialIntegrationTest",
    "-y", "org.scalatest.WordSpec")),
  fork in UnstableTest := true,

  fork in SerialIntegrationTest := true,
  testOptions in SerialIntegrationTest := Seq(formattingTestArg(target.value / "test-reports" / "serial-integration"),
    Tests.Argument(
      "-n", "mesosphere.marathon.SerialIntegrationTest",
      "-l", "mesosphere.marathon.UnstableTest",
      "-y", "org.scalatest.WordSpec")),
  parallelExecution in SerialIntegrationTest := false,
  testForkedParallel in SerialIntegrationTest := false,

  fork in IntegrationTest := true,
  testOptions in IntegrationTest := Seq(formattingTestArg(target.value / "test-reports" / "integration"),
    Tests.Argument(
      "-n", "mesosphere.marathon.IntegrationTest",
      "-l", "mesosphere.marathon.SerialIntegrationTest",
      "-l", "mesosphere.marathon.UnstableTest",
      "-y", "org.scalatest.WordSpec")),
  parallelExecution in IntegrationTest := true,
  testForkedParallel in IntegrationTest := true,
  concurrentRestrictions in IntegrationTest := Seq(Tags.limitAll(math.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2))),
  test in IntegrationTest := {
    (test in IntegrationTest).value
    (test in SerialIntegrationTest).value
  },

  fork in UnstableIntegrationTest := true,
  testOptions in UnstableIntegrationTest := Seq(formattingTestArg(target.value / "test-reports" / "unstable-integration"),
    Tests.Argument(
      "-n", "mesosphere.marathon.IntegrationTest",
      "-l", "mesosphere.marathon.SerialIntegrationTest",
      "-y", "org.scalatest.WordSpec")),
  parallelExecution in UnstableIntegrationTest := true,

  scapegoatVersion := "1.3.0",

  coverageMinimum := 62,
  coverageFailOnMinimum := true,

  fork in run := true,
  AspectjKeys.aspectjVersion in Aspectj := "1.8.10",
  AspectjKeys.inputs in Aspectj += compiledClasses.value,
  products in Compile := (products in Aspectj).value,
  products in Runtime := (products in Aspectj).value,
  products in Compile := (products in Aspectj).value,
  AspectjKeys.showWeaveInfo := true,
  AspectjKeys.verbose := true,
  // required for AJC compile time weaving
  javacOptions in Compile += "-g",
  javaOptions in run ++= (AspectjKeys.weaverOptions in Aspectj).value,
  javaOptions in Test ++= (AspectjKeys.weaverOptions in Aspectj).value,
  // non-tagged builds use this. Should _always_ end in snapshot.
  git.baseVersion := "1.5.0-SNAPSHOT"
)

val aopMerge: sbtassembly.MergeStrategy = new sbtassembly.MergeStrategy {
  val name = "aopMerge"
  import scala.xml._
  import scala.xml.dtd._

  def apply(tempDir: File, path: String, files: Seq[File]): Either[String, Seq[(File, String)]] = {
    val dt = DocType("aspectj", PublicID("-//AspectJ//DTD//EN", "http://www.eclipse.org/aspectj/dtd/aspectj.dtd"), Nil)
    val file = MergeStrategy.createMergeTarget(tempDir, path)
    val xmls: Seq[Elem] = files.map(XML.loadFile)
    val aspectsChildren: Seq[Node] = xmls.flatMap(_ \\ "aspectj" \ "aspects" \ "_")
    val weaverChildren: Seq[Node] = xmls.flatMap(_ \\ "aspectj" \ "weaver" \ "_")
    val options: String = xmls.map(x => (x \\ "aspectj" \ "weaver" \ "@options").text).mkString(" ").trim
    val weaverAttr = if (options.isEmpty) Null else new UnprefixedAttribute("options", options, Null)
    val aspects = new Elem(null, "aspects", Null, TopScope, false, aspectsChildren: _*)
    val weaver = new Elem(null, "weaver", weaverAttr, TopScope, false, weaverChildren: _*)
    val aspectj = new Elem(null, "aspectj", Null, TopScope, false, aspects, weaver)
    XML.save(file.toString, aspectj, "UTF-8", xmlDecl = false, dt)
    IO.append(file, IO.Newline.getBytes(IO.defaultCharset))
    Right(Seq(file -> path))
  }
}

// TODO: Move away from sbt-assembly, favoring sbt-native-packager
lazy val asmSettings = Seq(
  assemblyMergeStrategy in assembly := {
    case "application.conf" => MergeStrategy.concat
    case "META-INF/jersey-module-version" => MergeStrategy.first
    case "META-INF/aop.xml" => aopMerge
    case "org/apache/hadoop/yarn/util/package-info.class" => MergeStrategy.first
    case "org/apache/hadoop/yarn/factories/package-info.class" => MergeStrategy.first
    case "org/apache/hadoop/yarn/factory/providers/package-info.class" => MergeStrategy.first
    case x => (assemblyMergeStrategy in assembly).value(x)
  },
  assemblyExcludedJars in assembly := {
    val exclude = Set(
      "commons-beanutils-1.7.0.jar",
      "stax-api-1.0.1.jar",
      "commons-beanutils-core-1.8.0.jar",
      "servlet-api-2.5.jar",
      "jsp-api-2.1.jar"
    )
    (fullClasspath in assembly).value.filter { x => exclude(x.data.getName) }
  },
  test in assembly := {}
)

lazy val packagingSettings = Seq(
  dockerBaseImage in Docker := "openjdk:8u121-jdk",
  dockerExposedPorts in Docker := Seq(8080),
  dockerRepository in Docker := Some("mesosphere"),
  dockerCommands ++= Seq(
    ExecCmd("RUN", "apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF && \\" +
    "echo \"deb http://repos.mesosphere.com/debian jessie-testing main\" | tee -a /etc/apt/sources.list.d/mesosphere.list && \\" +
    "echo \"deb http://repos.mesosphere.com/debian jessie main\" | tee -a /etc/apt/sources.list.d/mesosphere.list && \\" +
    "apt-get update && \\" +
    s"apt-get install --no-install-recommends -y --force-yes mesos=${Dependency.V.MesosDebian} && \\" +
    "apt-get clean")
  )
)

lazy val `plugin-interface` = (project in file("plugin-interface"))
    .enablePlugins(GitBranchPrompt, CopyPasteDetector)
    .configs(SerialIntegrationTest)
    .configs(IntegrationTest)
    .configs(UnstableTest)
    .configs(UnstableIntegrationTest)
    .settings(commonSettings : _*)
    .settings(formatSettings : _*)
    .settings(
      name := "plugin-interface",
      libraryDependencies ++= Dependencies.pluginInterface
    )

lazy val marathon = (project in file("."))
  .configs(SerialIntegrationTest)
  .configs(IntegrationTest)
  .configs(UnstableTest)
  .configs(UnstableIntegrationTest)
  .enablePlugins(GitBranchPrompt, JavaServerAppPackaging, DockerPlugin,
    CopyPasteDetector, RamlGeneratorPlugin, DoublePackagePlugin, GitVersioning)
  .dependsOn(`plugin-interface`)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .settings(teamCitySetEnvSettings: _*)
  .settings(asmSettings: _*)
  .settings(
    unmanagedResourceDirectories in Compile += file("docs/docs/rest-api"),
    libraryDependencies ++= Dependencies.marathon,
    sourceGenerators in Compile += (ramlGenerate in Compile).taskValue,
    scapegoatIgnoredFiles ++= Seq(s"${sourceManaged.value.getPath}/.*"),
    mainClass in Compile := Some("mesosphere.marathon.Main"),
    packageOptions in (Compile, packageBin) ++= Seq(
      Package.ManifestAttributes("Implementation-Version" -> version.value ),
      Package.ManifestAttributes("Scala-Version" -> scalaVersion.value ),
      Package.ManifestAttributes("Git-Commit" -> git.gitHeadCommit.value.getOrElse("unknown") )
    )
  )

lazy val `mesos-simulation` = (project in file("mesos-simulation"))
  .configs(SerialIntegrationTest)
  .configs(IntegrationTest)
  .configs(UnstableTest)
  .configs(UnstableIntegrationTest)
  .enablePlugins(GitBranchPrompt, CopyPasteDetector)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    name := "mesos-simulation"
  )

// see also, benchmark/README.md
lazy val benchmark = (project in file("benchmark"))
  .configs(SerialIntegrationTest)
  .configs(IntegrationTest)
  .configs(UnstableTest)
  .configs(UnstableIntegrationTest)
  .enablePlugins(JmhPlugin, GitBranchPrompt, CopyPasteDetector)
  .settings(commonSettings : _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    testOptions in Test += Tests.Argument(TestFrameworks.JUnit),
    libraryDependencies ++= Dependencies.benchmark,
    generatorType in Jmh := "asm"
  )
