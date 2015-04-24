import sbt._
import Keys._
import sbtassembly.Plugin._
import AssemblyKeys._
import sbtrelease.ReleasePlugin._
import com.typesafe.sbt.SbtScalariform._
import net.virtualvoid.sbt.graph.Plugin.graphSettings
import ohnosequences.sbt.SbtS3Resolver.S3Resolver
import ohnosequences.sbt.SbtS3Resolver.{ s3, s3resolver }
import org.scalastyle.sbt.ScalastylePlugin.{ Settings => styleSettings }
import scalariform.formatter.preferences._
import sbtbuildinfo.Plugin._
import spray.revolver.RevolverPlugin.Revolver.{settings => revolverSettings}

object MarathonBuild extends Build {
  lazy val root: Project = Project(
    id = "marathon",
    base = file("."),
    settings = baseSettings ++
               asmSettings ++
               releaseSettings ++
               publishSettings ++
               formatSettings ++
               scalaStyleSettings ++
               revolverSettings ++
               graphSettings ++
               testSettings ++
               integrationTestSettings ++
      Seq(
        libraryDependencies ++= Dependencies.root,
        parallelExecution in Test := false,
        fork in Test := true
      )
    )
    .configs(IntegrationTest)
    // run mesos-simulation/test:test when running test
    .settings((test in Test) <<= (test in Test) dependsOn (test in Test in LocalProject("mesosSimulation")))

  lazy val mesosSimulation: Project = Project(
    id = "mesosSimulation",
    base = file("mesos-simulation"),
    settings = baseSettings ++
      formatSettings ++
      scalaStyleSettings ++
      revolverSettings ++
      testSettings ++
      integrationTestSettings
    ).dependsOn(root % "compile->compile; test->test").configs(IntegrationTest)

  lazy val integrationTestSettings = inConfig(IntegrationTest)(Defaults.testTasks) ++
    Seq(
      testOptions in Test := Seq(Tests.Argument("-l", "integration")),
      testOptions in IntegrationTest := Seq(Tests.Argument("-n", "integration")))

  lazy val testSettings = Seq(
    parallelExecution in Test := false,
    fork in Test := true
  )

  lazy val testScalaStyle = taskKey[Unit]("testScalaStyle")

  lazy val scalaStyleSettings = styleSettings ++ Seq(
    testScalaStyle := {
      org.scalastyle.sbt.PluginKeys.scalastyle.toTask("").value
    },
    (test in Test) <<= (test in Test) dependsOn testScalaStyle
  )

  lazy val IntegrationTest = config("integration") extend Test

  lazy val baseSettings = Defaults.defaultSettings ++ buildInfoSettings ++ Seq (
    organization := "mesosphere",
    scalaVersion := "2.11.5",
    scalacOptions in Compile ++= Seq(
      "-encoding", "UTF-8",
      "-target:jvm-1.6",
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
    javacOptions in Compile ++= Seq("-encoding", "UTF-8", "-source", "1.6", "-target", "1.6", "-Xlint:unchecked", "-Xlint:deprecation"),
    resolvers ++= Seq(
      "Mesosphere Public Repo"    at "http://downloads.mesosphere.io/maven",
      "Twitter Maven2 Repository" at "http://maven.twttr.com/",
      "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/",
      "Spray Maven Repository"    at "http://repo.spray.io/"
    ),
    sourceGenerators in Compile <+= buildInfo,
    fork in Test := true,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion),
    buildInfoPackage := "mesosphere.marathon"
  )

  lazy val asmSettings = assemblySettings ++ Seq(
    mergeStrategy in assembly <<= (mergeStrategy in assembly) { old =>
      {
        case "application.conf"                                             => MergeStrategy.concat
        case "META-INF/jersey-module-version"                               => MergeStrategy.first
        case "log4j.properties"                                             => MergeStrategy.concat
        case "org/apache/hadoop/yarn/util/package-info.class"               => MergeStrategy.first
        case "org/apache/hadoop/yarn/factories/package-info.class"          => MergeStrategy.first
        case "org/apache/hadoop/yarn/factory/providers/package-info.class"  => MergeStrategy.first
        case x                                                              => old(x)
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

  lazy val publishSettings = S3Resolver.defaults ++ Seq(
    publishTo := Some(s3resolver.value(
      "Mesosphere Public Repo (S3)",
      s3("downloads.mesosphere.com/maven")
    ))
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
}

object Dependencies {
  import Dependency._

  val root = Seq(
    // runtime
    akkaActor % "compile",
    akkaSlf4j % "compile",
    sprayClient % "compile",
    sprayHttpx % "compile",
    chaos % "compile",
    mesosUtils % "compile",
    jacksonCaseClass % "compile",
    twitterCommons % "compile",
    twitterZkClient % "compile",
    jodaTime % "compile",
    jodaConvert % "compile",
    jerseyServlet % "compile",
    jerseyMultiPart % "compile",
    uuidGenerator % "compile",
    jGraphT % "compile",
    hadoopHdfs % "compile",
    hadoopCommon % "compile",
    beanUtils % "compile",
    scallop % "compile",
    playJson % "compile",

    // test
    Test.scalatest % "test",
    Test.mockito % "test",
    Test.akkaTestKit % "test"
  )
}

object Dependency {
  object V {
    // runtime deps versions
    val Chaos = "0.6.5"
    val JacksonCCM = "0.1.2"
    val MesosUtils = "0.22.0-1"
    val Akka = "2.3.9"
    val Spray = "1.3.2"
    val TwitterCommons = "0.0.76"
    val TwitterZKClient = "0.0.70"
    val Jersey = "1.18.1"
    val JodaTime = "2.3"
    val JodaConvert = "1.6"
    val UUIDGenerator = "3.1.3"
    val JGraphT = "0.9.1"
    val Hadoop = "2.4.1"
    val Scallop = "0.9.5"
    val PlayJson = "2.3.7"

    // test deps versions
    val Mockito = "1.9.5"
    val ScalaTest = "2.1.7"
  }

  val excludeMortbayJetty = ExclusionRule(organization = "org.mortbay.jetty")
  val excludeJavaxServlet = ExclusionRule(organization = "javax.servlet")

  val akkaActor = "com.typesafe.akka" %% "akka-actor" % V.Akka
  val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % V.Akka
  val sprayClient = "io.spray" %% "spray-client" % V.Spray
  val sprayHttpx = "io.spray" %% "spray-httpx" % V.Spray
  val playJson = "com.typesafe.play" %% "play-json" % V.PlayJson
  val chaos = "mesosphere" %% "chaos" % V.Chaos
  val mesosUtils = "mesosphere" %% "mesos-utils" % V.MesosUtils
  val jacksonCaseClass = "mesosphere" %% "jackson-case-class-module" % V.JacksonCCM
  val jerseyServlet =  "com.sun.jersey" % "jersey-servlet" % V.Jersey
  val jerseyMultiPart =  "com.sun.jersey.contribs" % "jersey-multipart" % V.Jersey
  val jodaTime = "joda-time" % "joda-time" % V.JodaTime
  val jodaConvert = "org.joda" % "joda-convert" % V.JodaConvert
  val twitterCommons = "com.twitter.common.zookeeper" % "candidate" % V.TwitterCommons
  val twitterZkClient = "com.twitter.common.zookeeper" % "client" % V.TwitterZKClient
  val uuidGenerator = "com.fasterxml.uuid" % "java-uuid-generator" % V.UUIDGenerator
  val jGraphT = "org.javabits.jgrapht" % "jgrapht-core" % V.JGraphT
  val hadoopHdfs = "org.apache.hadoop" % "hadoop-hdfs" % V.Hadoop excludeAll(excludeMortbayJetty, excludeJavaxServlet)
  val hadoopCommon = "org.apache.hadoop" % "hadoop-common" % V.Hadoop excludeAll(excludeMortbayJetty, excludeJavaxServlet)
  val beanUtils = "commons-beanutils" % "commons-beanutils" % "1.9.2"
  val scallop = "org.rogach" %% "scallop" % V.Scallop

  object Test {
    val scalatest = "org.scalatest" %% "scalatest" % V.ScalaTest
    val mockito = "org.mockito" % "mockito-all" % V.Mockito
    val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % V.Akka
  }
}
