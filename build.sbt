import com.amazonaws.auth.{EnvironmentVariableCredentialsProvider, InstanceProfileCredentialsProvider}
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.packager.docker.ExecCmd
import mesosphere.raml.RamlGeneratorPlugin
import sbt.Tests.SubProcess
import sbtrelease.ReleaseStateTransformations._

import scalariform.formatter.preferences.{AlignArguments, AlignParameters, AlignSingleLineCaseStatements, CompactControlReadability, DanglingCloseParenthesis, DoubleIndentClassDeclaration, FormatXml, FormattingPreferences, IndentSpaces, IndentWithTabs, MultilineScaladocCommentsStartOnFirstLine, PlaceScaladocAsterisksBeneathSecondAsterisk, Preserve, PreserveSpaceBeforeArguments, SpaceBeforeColon, SpaceInsideBrackets, SpaceInsideParentheses, SpacesAroundMultiImports, SpacesWithinPatternBinders}

lazy val IntegrationTest = config("integration") extend Test
lazy val formattingTestArg = Tests.Argument("-eDFG")

// 0.1.15 has tons of false positives in async/await
resolvers += Resolver.sonatypeRepo("snapshots")
addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % "0.1-SNAPSHOT")

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

lazy val commonSettings = inConfig(IntegrationTest)(Defaults.testTasks) ++ Seq(
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
    "-Ywarn-unused-import",
    "-Xfatal-warnings",
    "-Yno-adapted-args",
    "-Ywarn-numeric-widen"
  ),
  scalacOptions in Test ~= { _.filter(co => !(co.startsWith("-Xplugin") || co.startsWith("-P"))) },
  javacOptions in Compile ++= Seq(
    "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation"
  ),
  resolvers ++= Seq(
    "Mesosphere Public Repo" at "http://downloads.mesosphere.com/maven",
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/",
    "Spray Maven Repository" at "http://repo.spray.io/"
  ),
  cancelable in Global := true,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    pushChanges
  ),

  publishTo := Some(s3resolver.value(
    "Mesosphere Public Repo (S3)",
    s3("downloads.mesosphere.io/maven")
  )),
  s3credentials := new EnvironmentVariableCredentialsProvider() | new InstanceProfileCredentialsProvider(),

  parallelExecution in Test := true,
  testForkedParallel in Test := true,
  testOptions in Test := Seq(formattingTestArg, Tests.Argument("-l", "mesosphere.marathon.IntegrationTest")),
  fork in Test := true,

  fork in IntegrationTest := true,
  testOptions in IntegrationTest := Seq(formattingTestArg, Tests.Argument("-n", "mesosphere.marathon.IntegrationTest")),
  parallelExecution in IntegrationTest := false,
  testForkedParallel in IntegrationTest := false,
  testListeners in IntegrationTest := Seq(new JUnitXmlTestsListener((target.value / "test-reports" / "integration").getAbsolutePath)),
  testGrouping in IntegrationTest := (definedTests in IntegrationTest).value.map { test =>
    Tests.Group(name = test.name, tests = Seq(test),
      runPolicy = SubProcess(ForkOptions((javaHome in IntegrationTest).value,
        (outputStrategy in IntegrationTest).value, Nil, Some(baseDirectory.value),
        (javaOptions in IntegrationTest).value, (connectInput in IntegrationTest).value,
        (envVars in IntegrationTest).value
      )))
  },

  scapegoatVersion := "1.2.1"

)

// TODO: Move away from sbt-assembly, favoring sbt-native-packager
lazy val asmSettings = Seq(
  assemblyMergeStrategy in assembly <<= (assemblyMergeStrategy in assembly) { old =>
  {
    case "application.conf"                                             => MergeStrategy.concat
    case "META-INF/jersey-module-version"                               => MergeStrategy.first
    case "org/apache/hadoop/yarn/util/package-info.class"               => MergeStrategy.first
    case "org/apache/hadoop/yarn/factories/package-info.class"          => MergeStrategy.first
    case "org/apache/hadoop/yarn/factory/providers/package-info.class"  => MergeStrategy.first
    case x                                                              => old(x)
  }
  },
  assemblyExcludedJars in assembly <<= (fullClasspath in assembly) map { cp =>
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

lazy val packagingSettings = Seq(
  dockerBaseImage in Docker := "java:8-jdk",
  dockerExposedPorts in Docker := Seq(8080),
  dockerRepository in Docker := Some("mesosphere"),
  dockerCommands ++= Seq(
    ExecCmd("RUN", "apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF && \\" +
    "echo \"deb http://repos.mesosphere.com/debian jessie-testing main\" | tee -a /etc/apt/sources.list.d/mesosphere.list && \\" +
    "echo \"deb http://repos.mesosphere.com/debian jessie main\" | tee -a /etc/apt/sources.list.d/mesosphere.list && \\" +
    "apt-get update && \\" +
    "apt-get install --no-install-recommends -y --force-yes mesos=1.0.0-2.0.89.debian81 && \\" +
    "apt-get clean")
  )
)

lazy val `plugin-interface` = (project in file("plugin-interface"))
    .enablePlugins(GitBranchPrompt, CopyPasteDetector)
    .configs(IntegrationTest)
    .settings(commonSettings : _*)
    .settings(formatSettings : _*)
    .settings(
      name := "plugin-interface",
      libraryDependencies ++= Dependencies.pluginInterface
    )

lazy val marathon = (project in file("."))
  .configs(IntegrationTest)
  .enablePlugins(BuildInfoPlugin, GitBranchPrompt,
    JavaServerAppPackaging, DockerPlugin, CopyPasteDetector, RamlGeneratorPlugin)
  .dependsOn(`plugin-interface`)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .settings(teamCitySetEnvSettings: _*)
  .settings(asmSettings: _*)
  .settings(
    name := "marathon",
    unmanagedResourceDirectories in Compile += file("docs/docs/rest-api"),
    libraryDependencies ++= Dependencies.marathon,
    buildInfoKeys := Seq(
      name, version, scalaVersion,
      BuildInfoKey.action("buildref") {
        git.gitHeadCommit.value.getOrElse("unknown")
      }
    ),
    buildInfoPackage := "mesosphere.marathon",
    sourceGenerators in Compile <+= ramlGenerate in Compile,
    scapegoatIgnoredFiles ++= Seq(s"${sourceManaged.value.getPath}/.*")
  )

lazy val `mesos-simulation` = (project in file("mesos-simulation"))
    .configs(IntegrationTest)
    .enablePlugins(GitBranchPrompt, CopyPasteDetector)
    .settings(commonSettings: _*)
    .settings(formatSettings: _*)
    .dependsOn(marathon % "compile->compile; test->test")
    .settings(
      name := "mesos-simulation"
    )

// see also, benchmark/README.md
lazy val benchmark = (project in file("benchmark"))
  .configs(IntegrationTest)
  .enablePlugins(JmhPlugin, GitBranchPrompt, CopyPasteDetector)
  .settings(commonSettings : _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    testOptions in Test += Tests.Argument(TestFrameworks.JUnit),
    libraryDependencies ++= Dependencies.benchmark
  )

