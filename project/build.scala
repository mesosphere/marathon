import com.amazonaws.auth.{ EnvironmentVariableCredentialsProvider, InstanceProfileCredentialsProvider }
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import ohnosequences.sbt.SbtS3Resolver.autoImport._
import org.scalastyle.sbt.ScalastylePlugin.{ buildSettings => styleSettings }
import sbt.Keys._
import sbt._
import sbtassembly.AssemblyKeys._
import sbtassembly.MergeStrategy
import sbtbuildinfo.BuildInfoKeys._
import sbtbuildinfo.{ BuildInfoKey, BuildInfoPlugin }
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease._
import scala.util.Try

import scalariform.formatter.preferences._

object MarathonBuild extends Build {
  lazy val pluginInterface: Project = Project(
    id = "plugin-interface",
    base = file("plugin-interface"),
    settings = baseSettings ++
      asmSettings ++
      formatSettings ++
      scalaStyleSettings ++
      publishSettings ++
      Seq(
        libraryDependencies ++= Dependencies.pluginInterface
      )
  )

  lazy val root: Project = Project(
    id = "marathon",
    base = file("."),
    settings = baseSettings ++
      asmSettings ++
      customReleaseSettings ++
      formatSettings ++
      scalaStyleSettings ++
      testSettings ++
      integrationTestSettings ++
      benchmarkSettings ++
      teamCitySetEnvSettings ++
      publishSettings ++
      Seq(
        unmanagedResourceDirectories in Compile += file("docs/docs/rest-api"),
        libraryDependencies ++= Dependencies.root,
        parallelExecution in Test := false,
        buildInfoKeys := Seq[BuildInfoKey](
          name, version, scalaVersion,
          BuildInfoKey.action("buildref") {
            val suffix = Try(Process("git diff --shortstat").lines.headOption.map(_ => "-dev")).toOption.flatten.getOrElse("")
            Try(Process("git rev-parse HEAD").lines.headOption).toOption.flatten.getOrElse("unknown") + suffix
          }),
        buildInfoPackage := "mesosphere.marathon",
        fork in Test := true
      )
  )
    .configs(IntegrationTest, Benchmark)
    .enablePlugins(BuildInfoPlugin)
    .dependsOn(pluginInterface)
    // run mesos-simulation/test:test when running test
    .settings((test in Test) <<= (test in Test) dependsOn (test in Test in LocalProject("mesos-simulation")))

  lazy val mesosSimulation: Project = Project(
    id = "mesos-simulation",
    base = file("mesos-simulation"),
    settings = baseSettings ++
      formatSettings ++
      scalaStyleSettings ++
      testSettings ++
      integrationTestSettings ++
      benchmarkSettings
    ).dependsOn(root % "compile->compile; test->test").configs(IntegrationTest, Benchmark)

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

  lazy val benchmarkSettings = inConfig(Benchmark)(Defaults.testTasks) ++
    Seq(
      testFrameworks in Benchmark := Seq(new TestFramework("org.scalameter.ScalaMeterFramework")),
      testOptions in Benchmark := Seq(),
      logBuffered in Benchmark := false,
      parallelExecution in Benchmark := false,
      fork in Benchmark := true
    )

  lazy val integrationTestSettings = inConfig(IntegrationTest)(Defaults.testTasks) ++
    Seq(
      testOptions in IntegrationTest := Seq(formattingTestArg, Tests.Argument("-n", "mesosphere.marathon.IntegrationTest"))
    )

  lazy val testSettings = Seq(
    parallelExecution in Test := false,
    testOptions in Test := Seq(formattingTestArg, Tests.Argument("-l", "mesosphere.marathon.IntegrationTest")),
    fork in Test := true
  )

  lazy val testScalaStyle = taskKey[Unit]("testScalaStyle")

  lazy val scalaStyleSettings = styleSettings ++ Seq(
    testScalaStyle := {
      org.scalastyle.sbt.ScalastylePlugin.scalastyle.in(Compile).toTask("").value
    },
    (compile in Test) <<= (compile in Test) dependsOn testScalaStyle
  )

  lazy val IntegrationTest = config("integration") extend Test
  lazy val Benchmark = config("bench") extend Test

  lazy val baseSettings = Seq (
    organization := "mesosphere.marathon",
    scalaVersion := "2.11.8",
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
    javacOptions in Compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation"),
    resolvers ++= Seq(
      "Mesosphere Public Repo"    at "http://downloads.mesosphere.com/maven",
      "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/",
      "Spray Maven Repository"    at "http://repo.spray.io/"
    ),
    cancelable in Global := true,
    fork in Test := true,
    javaOptions += "-Xmx4G"
  )

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

  /**
   * This is the standard release process without
   * -publishArtifacts
   * -setNextVersion
   * -commitNextVersion
   */
  lazy val customReleaseSettings = Seq(
    releaseProcess := Seq[ReleaseStep](
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

  lazy val publishSettings = Seq(
    publishTo := Some(s3resolver.value(
      "Mesosphere Public Repo (S3)",
      s3("downloads.mesosphere.io/maven")
    )),
    s3credentials := new EnvironmentVariableCredentialsProvider() |
      new InstanceProfileCredentialsProvider()
  )
}

object Dependencies {
  import Dependency._

  val pluginInterface = Seq(
    playJson % "compile",
    mesosUtils % "compile",
    guava % "compile",
    wixAccord % "compile"
  )

  val excludeSlf4jLog4j12 = ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
  val excludeLog4j = ExclusionRule(organization = "log4j")
  val excludeJCL = ExclusionRule(organization = "commons-logging")

  val root = Seq(
    // runtime
    akkaActor % "compile",
    akkaSlf4j % "compile",
    akkaStream % "compile",
    akkaHttp % "compile",
    asyncAwait % "compile",
    sprayClient % "compile",
    sprayHttpx % "compile",
    chaos % "compile",
    mesosUtils % "compile",
    twitterCommons % "compile",
    jodaTime % "compile",
    jodaConvert % "compile",
    jerseyServlet % "compile",
    jerseyMultiPart % "compile",
    jettyEventSource % "compile",
    uuidGenerator % "compile",
    jGraphT % "compile",
    hadoopHdfs % "compile",
    hadoopCommon % "compile",
    beanUtils % "compile",
    playJson % "compile",
    jsonSchemaValidator % "compile",
    twitterZk % "compile",
    rxScala % "compile",
    marathonUI % "compile",
    graphite % "compile",
    datadog % "compile",
    marathonApiConsole % "compile",
    wixAccord % "compile",
    curator % "compile",
    curatorClient % "compile",
    curatorFramework % "compile",
    java8Compat % "compile",
    scalaLogging % "compile",
    logstash % "compile",

    // test
    Test.diffson % "test",
    Test.scalatest % "test",
    Test.mockito % "test",
    Test.akkaTestKit % "test",
    Test.junit % "test",
    Test.scalameter % "test"
  ).map(_.excludeAll(excludeSlf4jLog4j12).excludeAll(excludeLog4j).excludeAll(excludeJCL))
}

object Dependency {
  object V {
    // runtime deps versions
    val Chaos = "0.8.7"
    val Guava = "19.0"
    // FIXME (gkleiman): reenable deprecation checks after Mesos 1.0.0-rc2 deprecations are handled
    val MesosUtils = "1.0.0"
    val Akka = "2.4.7"
    val AsyncAwait = "0.9.6-RC2"
    val Spray = "1.3.3"
    val TwitterCommons = "0.0.76"
    val TwitterZk = "6.34.0"
    val Jersey = "1.18.1"
    val JettyServlets = "9.3.6.v20151106"
    val JodaTime = "2.9.4"
    val JodaConvert = "1.8.1"
    val UUIDGenerator = "3.1.4"
    val JGraphT = "0.9.3"
    val Hadoop = "2.7.2"
    val Diffson = "2.0.2"
    val PlayJson = "2.5.4"
    val JsonSchemaValidator = "2.2.6"
    val RxScala = "0.26.2"
    val MarathonUI = "1.1.3"
    val MarathonApiConsole = "0.1.1"
    val Graphite = "3.1.2"
    val DataDog = "1.1.5"
    val Logback = "1.1.3"
    val Logstash = "4.7"
    val WixAccord = "0.5"
    val Curator = "2.10.0"
    val Java8Compat = "0.8.0-RC1"
    val ScalaLogging = "3.4.0"

    // test deps versions
    val Mockito = "1.10.19"
    val ScalaTest = "2.2.6"
    val JUnit = "4.12"
    val ScalaMeter = "0.7"
  }

  val excludeMortbayJetty = ExclusionRule(organization = "org.mortbay.jetty")
  val excludeJavaxServlet = ExclusionRule(organization = "javax.servlet")

  val akkaActor = "com.typesafe.akka" %% "akka-actor" % V.Akka
  val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % V.Akka
  val akkaStream = "com.typesafe.akka" %% "akka-stream" % V.Akka
  val akkaHttp = "com.typesafe.akka" %% "akka-http-experimental" % V.Akka
  val asyncAwait = "org.scala-lang.modules" %% "scala-async" % V.AsyncAwait
  val sprayClient = "io.spray" %% "spray-client" % V.Spray
  val sprayHttpx = "io.spray" %% "spray-httpx" % V.Spray
  val playJson = "com.typesafe.play" %% "play-json" % V.PlayJson
  val chaos = "mesosphere" %% "chaos" % V.Chaos exclude("org.glassfish.web", "javax.el")
  val guava = "com.google.guava" % "guava" % V.Guava
  val mesosUtils = "mesosphere" %% "mesos-utils" % V.MesosUtils
  val jerseyServlet =  "com.sun.jersey" % "jersey-servlet" % V.Jersey
  val jettyEventSource = "org.eclipse.jetty" % "jetty-servlets" % V.JettyServlets
  val jerseyMultiPart =  "com.sun.jersey.contribs" % "jersey-multipart" % V.Jersey
  val jodaTime = "joda-time" % "joda-time" % V.JodaTime
  val jodaConvert = "org.joda" % "joda-convert" % V.JodaConvert
  val twitterCommons = "com.twitter.common.zookeeper" % "candidate" % V.TwitterCommons
  val uuidGenerator = "com.fasterxml.uuid" % "java-uuid-generator" % V.UUIDGenerator
  val jGraphT = "org.javabits.jgrapht" % "jgrapht-core" % V.JGraphT
  val hadoopHdfs = "org.apache.hadoop" % "hadoop-hdfs" % V.Hadoop excludeAll(excludeMortbayJetty, excludeJavaxServlet)
  val hadoopCommon = "org.apache.hadoop" % "hadoop-common" % V.Hadoop excludeAll(excludeMortbayJetty,
    excludeJavaxServlet)
  val beanUtils = "commons-beanutils" % "commons-beanutils" % "1.9.2"
  val jsonSchemaValidator = "com.github.fge" % "json-schema-validator" % V.JsonSchemaValidator
  val twitterZk = "com.twitter" %% "util-zk" % V.TwitterZk
  val rxScala = "io.reactivex" %% "rxscala" % V.RxScala
  val marathonUI = "mesosphere.marathon" % "ui" % V.MarathonUI
  val marathonApiConsole = "mesosphere.marathon" % "api-console" % V.MarathonApiConsole
  val graphite = "io.dropwizard.metrics" % "metrics-graphite" % V.Graphite
  val datadog = "org.coursera" % "dropwizard-metrics-datadog" % V.DataDog exclude("ch.qos.logback", "logback-classic")
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % V.Logstash
  val wixAccord = "com.wix" %% "accord-core" % V.WixAccord
  val curator = "org.apache.curator" % "curator-recipes" % V.Curator
  val curatorClient = "org.apache.curator" % "curator-client" % V.Curator
  val curatorFramework = "org.apache.curator" % "curator-framework" % V.Curator
  val java8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % V.Java8Compat
  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % V.ScalaLogging

  object Test {
    val scalatest = "org.scalatest" %% "scalatest" % V.ScalaTest
    val mockito = "org.mockito" % "mockito-all" % V.Mockito
    val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % V.Akka
    val diffson = "org.gnieh" %% "diffson" % V.Diffson
    val junit = "junit" % "junit" % V.JUnit
    val scalameter = "com.storm-enroute" %% "scalameter" % V.ScalaMeter
  }
}
