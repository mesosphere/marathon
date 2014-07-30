import sbt._
import Keys._
import sbtassembly.Plugin._
import AssemblyKeys._
import sbtrelease.ReleasePlugin._
import com.typesafe.sbt.SbtScalariform._
import ohnosequences.sbt.SbtS3Resolver.S3Resolver
import ohnosequences.sbt.SbtS3Resolver.{ s3, s3resolver }
import scalariform.formatter.preferences._
import sbtbuildinfo.Plugin._
import spray.revolver.RevolverPlugin.Revolver.{settings => revolverSettings}

object MarathonBuild extends Build {
  lazy val root = Project(
    id = "marathon",
    base = file("."),
    settings = baseSettings ++ asmSettings ++ releaseSettings ++ publishSettings ++ formatSettings ++ revolverSettings ++ Seq(
      libraryDependencies ++= Dependencies.root,
      parallelExecution in Test := false,
      fork in Test := true
    ))
    .configs(IntegrationTest)
    .settings(inConfig(IntegrationTest)(Defaults.testTasks): _*)
    .settings(testOptions in Test := Seq(Tests.Argument("-l", "integration")))
    .settings(testOptions in IntegrationTest := Seq(Tests.Argument("-n", "integration")))

  lazy val IntegrationTest = config("integration") extend Test

  lazy val baseSettings = Defaults.defaultSettings ++ buildInfoSettings ++ Seq (
    organization := "mesosphere",
    scalaVersion := "2.10.4",
    scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.6", "-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8", "-source", "1.6", "-target", "1.6", "-Xlint:unchecked", "-Xlint:deprecation"),
    resolvers ++= Seq(
      "Mesosphere Public Repo"    at "http://downloads.mesosphere.io/maven",
      "Twitter Maven2 Repository" at "http://maven.twttr.com/",
      "Spray Maven Repository"    at "http://repo.spray.io/",
      "Local Maven"               at s"${Path.userHome.asFile.toURI.toURL}.m2/repository"
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
        "servlet-api-2.5.jar"
      )
      cp filter { x => exclude(x.data.getName) }
    }
  )

  lazy val publishSettings = S3Resolver.defaults ++ Seq(
    publishTo := Some(s3resolver.value(
      "Mesosphere Public Repo (S3)",
      s3("downloads.mesosphere.io/maven")
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
    json4s % "compile",
    chaos % "compile",
    mesosUtils % "compile",
    jacksonCaseClass % "compile",
    mesos % "compile",
    twitterCommons % "compile",
    twitterZkClient % "compile",
    jodaTime % "compile",
    jodaConvert % "compile",
    jerseyServlet % "compile",
    jerseyMultiPart % "compile",
    uuidGenerator % "compile",
    jGraphT % "compile",
    hadoopClient % "compile",
    beanUtils % "compile",

    // test
    Test.scalatest % "test",
    Test.mockito % "test",
    Test.akkaTestKit % "test"
  )
}

object Dependency {
  object V {
    // runtime deps versions
    val Chaos = "0.5.6"
    val JacksonCCM = "0.1.0"
    val Mesos = "0.20.0"
    val MesosUtils = "0.19.0-1"
    val Akka = "2.2.4"
    val Spray = "1.2.1"
    val Json4s = "3.2.5"
    val TwitterCommons = "0.0.52"
    val TwitterZKClient = "0.0.43"
    val Jersey = "1.18.1"
    val JodaTime = "2.3"
    val JodaConvert = "1.6"
    val UUIDGenerator = "3.1.3"
    val JGraphT = "0.9.1"
    val Hadoop = "2.4.1"

    // test deps versions
    val Mockito = "1.9.5"
    val ScalaTest = "2.1.7"
  }

  val akkaActor = "com.typesafe.akka" %% "akka-actor" % V.Akka
  val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % V.Akka
  val sprayClient = "io.spray" % "spray-client" % V.Spray
  val sprayHttpx = "io.spray" % "spray-httpx" % V.Spray
  val json4s = "org.json4s" %% "json4s-jackson" % V.Json4s
  val chaos = "mesosphere" % "chaos" % V.Chaos
  val mesosUtils = "mesosphere" %% "mesos-utils" % V.MesosUtils
  val jacksonCaseClass = "mesosphere" %% "jackson-case-class-module" % V.JacksonCCM
  val jerseyServlet =  "com.sun.jersey" % "jersey-servlet" % V.Jersey
  val jerseyMultiPart =  "com.sun.jersey.contribs" % "jersey-multipart" % V.Jersey
  val jodaTime = "joda-time" % "joda-time" % V.JodaTime
  val jodaConvert = "org.joda" % "joda-convert" % V.JodaConvert
  val mesos = "org.apache.mesos" % "mesos" % V.Mesos
  val twitterCommons = "com.twitter.common.zookeeper" % "candidate" % V.TwitterCommons
  val twitterZkClient = "com.twitter.common.zookeeper" % "client" % V.TwitterZKClient
  val uuidGenerator = "com.fasterxml.uuid" % "java-uuid-generator" % V.UUIDGenerator
  val jGraphT = "org.javabits.jgrapht" % "jgrapht-core" % V.JGraphT
  val hadoopClient =  "org.apache.hadoop" % "hadoop-client" % V.Hadoop
  val beanUtils = "commons-beanutils" % "commons-beanutils" % "1.9.2"

  object Test {
    val scalatest = "org.scalatest" %% "scalatest" % V.ScalaTest
    val mockito = "org.mockito" % "mockito-all" % V.Mockito
    val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % V.Akka
  }
}

