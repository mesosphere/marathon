import sbt.ExclusionRule
import sbt._

object Dependencies {
  import Dependency._

  val pluginInterface = Seq(
    playJson % "compile",
    mesos % "compile",
    guava % "compile",
    wixAccord % "compile",
    scalaxml % "provided" // for scapegoat
  )

  val excludeSlf4jLog4j12 = ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
  val excludeLog4j = ExclusionRule(organization = "log4j")
  val excludeJCL = ExclusionRule(organization = "commons-logging")

  val marathon = Seq(
    // runtime
    akkaActor % "compile",
    akkaSlf4j % "compile",
    akkaStream % "compile",
    akkaHttp % "compile",
    asyncAwait % "compile",
    sprayClient % "compile",
    sprayHttpx % "compile",
    chaos % "compile",
    mesos % "compile",
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
    raven % "compile",
    akkaHttpPlayJson % "compile",

    // test
    Test.diffson % "test",
    Test.scalatest % "test",
    Test.mockito % "test",
    Test.akkaTestKit % "test",
    Test.junit % "test"
  ).map(_.excludeAll(excludeSlf4jLog4j12).excludeAll(excludeLog4j).excludeAll(excludeJCL))

  val benchmark = Seq(
    Test.jmh
  )
}

object Dependency {
  object V {
    // runtime deps versions
    val Chaos = "0.8.7"
    val Guava = "19.0"
    // FIXME (gkleiman): reenable deprecation checks after Mesos 1.0.0-rc2 deprecations are handled
    val Mesos = "1.1.0"
    // Version of Mesos to use in Dockerfile.
    val MesosDebian = "1.1.0-2.0.107.debian81"
    val Akka = "2.4.13"
    val AsyncAwait = "0.9.6"
    val Spray = "1.3.4"
    val TwitterCommons = "0.0.76"
    val TwitterZk = "6.34.0"
    val Jersey = "1.18.5"
    val JettyServlets = "9.3.6.v20151106"
    val JodaTime = "2.9.6"
    val JodaConvert = "1.8.1"
    val UUIDGenerator = "3.1.4"
    val JGraphT = "0.9.3"
    val Hadoop = "2.7.2"
    val Diffson = "2.0.2"
    val PlayJson = "2.5.10"
    val JsonSchemaValidator = "2.2.6"
    val RxScala = "0.26.4"
    val MarathonUI = "1.2.0"
    val MarathonApiConsole = "3.0.8"
    val Graphite = "3.1.2"
    val DataDog = "1.1.6"
    val Logback = "1.1.3"
    val Logstash = "4.8"
    val WixAccord = "0.5"
    val Curator = "2.11.1"
    val Java8Compat = "0.8.0"
    val ScalaLogging = "3.5.0"
    val Raven = "7.8.0"

    // test deps versions
    val Mockito = "1.10.19"
    val ScalaTest = "3.0.1"
    val JUnit = "4.12"
    val JUnitBenchmarks = "0.7.2"
    val JMH = "1.14"
  }

  val excludeMortbayJetty = ExclusionRule(organization = "org.mortbay.jetty")
  val excludeJavaxServlet = ExclusionRule(organization = "javax.servlet")

  val akkaActor = "com.typesafe.akka" %% "akka-actor" % V.Akka
  val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % V.Akka
  val akkaStream = "com.typesafe.akka" %% "akka-stream" % V.Akka
  val akkaHttp = "com.typesafe.akka" %% "akka-http-experimental" % "2.4.11"
  val akkaHttpPlayJson = "de.heikoseeberger" %% "akka-http-play-json" % "1.10.1"
  val asyncAwait = "org.scala-lang.modules" %% "scala-async" % V.AsyncAwait
  val sprayClient = "io.spray" %% "spray-client" % V.Spray
  val sprayHttpx = "io.spray" %% "spray-httpx" % V.Spray
  val playJson = "com.typesafe.play" %% "play-json" % V.PlayJson
  val chaos = "mesosphere" %% "chaos" % V.Chaos exclude("org.glassfish.web", "javax.el")
  val guava = "com.google.guava" % "guava" % V.Guava
  val mesos = "org.apache.mesos" % "mesos" % V.Mesos
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
  val beanUtils = "commons-beanutils" % "commons-beanutils" % "1.9.3"
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
  val scalaxml = "org.scala-lang.modules" %% "scala-xml" % "1.0.5"
  val raven = "com.getsentry.raven" % "raven-logback" % V.Raven

  object Test {
    val jmh = "org.openjdk.jmh" % "jmh-generator-annprocess" % V.JMH
    val scalatest = "org.scalatest" %% "scalatest" % V.ScalaTest
    val mockito = "org.mockito" % "mockito-all" % V.Mockito
    val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % V.Akka
    val diffson = "org.gnieh" %% "diffson" % V.Diffson
    val junit = "junit" % "junit" % V.JUnit
  }
}
