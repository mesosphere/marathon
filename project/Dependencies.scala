import sbt._

object Dependencies {

  import Dependency._

  val pluginInterface = Seq(
    playJson % "compile",
    guava % "compile"
  )

  val excludeSlf4jLog4j12 = ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
  val excludeLog4j = ExclusionRule(organization = "log4j")
  val excludeJCL = ExclusionRule(organization = "commons-logging")

  val root = Seq(
    // runtime
    akkaActor % "compile",
    akkaSlf4j % "compile",
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

    // test
    Test.diffson % "test",
    Test.scalatest % "test",
    Test.mockito % "test",
    Test.akkaTestKit % "test"
  ).map(_.excludeAll(excludeSlf4jLog4j12).excludeAll(excludeLog4j).excludeAll(excludeJCL))
}

object Dependency {

  object V {
    // runtime deps versions
    val Chaos = "0.8.6"
    val Guava = "18.0"
    val MesosUtils = "0.28.0"
    val Akka = "2.3.9"
    val Spray = "1.3.2"
    val TwitterCommons = "0.0.76"
    val TwitterZk = "6.24.0"
    val Jersey = "1.18.1"
    val JettyServlets = "9.3.2.v20150730"
    val JodaTime = "2.3"
    val JodaConvert = "1.6"
    val UUIDGenerator = "3.1.3"
    val JGraphT = "0.9.1"
    val Hadoop = "2.4.1"
    val Diffson = "0.3"
    val PlayJson = "2.4.3"
    val JsonSchemaValidator = "2.2.6"
    val RxScala = "0.25.0"
    val MarathonUI = "1.2.0"
    //    val MarathonUI = "1.2.0-SNAPSHOT"
    val MarathonApiConsole = "0.1.1"
    val Graphite = "3.1.2"
    val DataDog = "1.1.3"
    val Logback = "1.1.3"
    val WixAccord = "0.5"
    val Curator = "2.10.0"

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
  val chaos = "mesosphere" %% "chaos" % V.Chaos exclude("org.glassfish.web", "javax.el")
  val guava = "com.google.guava" % "guava" % V.Guava
  val mesosUtils = "mesosphere" %% "mesos-utils" % V.MesosUtils
  val jerseyServlet = "com.sun.jersey" % "jersey-servlet" % V.Jersey
  val jettyEventSource = "org.eclipse.jetty" % "jetty-servlets" % V.JettyServlets
  val jerseyMultiPart = "com.sun.jersey.contribs" % "jersey-multipart" % V.Jersey
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
  val wixAccord = "com.wix" %% "accord-core" % V.WixAccord
  val curator = "org.apache.curator" % "curator-recipes" % V.Curator

  object Test {
    val scalatest = "org.scalatest" %% "scalatest" % V.ScalaTest
    val mockito = "org.mockito" % "mockito-all" % V.Mockito
    val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % V.Akka
    val diffson = "org.gnieh" %% "diffson" % V.Diffson
  }

}
