import sbt.{ExclusionRule, _}

object Dependencies {
  import Dependency._

  val pluginInterface = Seq(
    playJson % "compile",
    mesos % "compile",
    guava % "compile",
    wixAccord % "compile",
    scalaLogging % "compile",
    scalaxml % "provided" // for scapegoat
  )

  val excludeSlf4jLog4j12 = ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
  val excludeLog4j = ExclusionRule(organization = "log4j", name = "log4j")
  val excludeJCL = ExclusionRule(organization = "commons-logging", name = "commons-logging")
  val excludeAkkaHttpExperimental = ExclusionRule(organization = "com.typesafe.akka", name = "akka-http-experimental_2.11")

  val marathon = (Seq(
    // runtime
    akkaActor % "compile",
    akkaSlf4j % "compile",
    akkaStream % "compile",
    akkaHttp % "compile",
    asyncAwait % "compile",
    aws % "compile",
    chaos % "compile",
    mesos % "compile",
    jodaTime % "compile",
    jodaConvert % "compile",
    jerseyServlet % "compile",
    jerseyMultiPart % "compile",
    jettyEventSource % "compile",
    uuidGenerator % "compile",
    jGraphT % "compile",
    beanUtils % "compile",
    playJson % "compile",
    jsonSchemaValidator % "compile",
    rxScala % "compile",
    marathonUI % "compile",
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
    alpakkaS3 % "compile",
    commonsCompress % "compile", // used for tar flow
    akkaSse % "compile",


    // test
    Test.diffson % "test",
    Test.scalatest % "test",
    Test.mockito % "test",
    Test.akkaTestKit % "test",
    Test.junit % "test",
    Test.scalacheck % "test",
    Test.wixAccordScalatest % "test",
    Test.curatorTest % "test",
    Test.commonsIO % "test"
  ) ++ Kamon.all).map(
    _.excludeAll(excludeSlf4jLog4j12)
     .excludeAll(excludeLog4j)
     .excludeAll(excludeJCL)
     .excludeAll(excludeAkkaHttpExperimental)
    )

  val benchmark = Seq(
    Test.jmh
  )
}

object Dependency {
  object V {
    // runtime deps versions
    val Aws = "1.11.128"
    val Alpakka  = "0.8"
    val Chaos = "0.8.8"
    val Guava = "19.0"
    val Mesos = "1.3.0-rc3"
    // Version of Mesos to use in Dockerfile.
    val MesosDebian = "1.2.0-2.0.6"
    val OpenJDK = "openjdk:8u121-jdk"
    val Akka = "2.4.18"
    val AkkaHttp = "10.0.6"
    val AkkaSSE = "2.0.0"
    val ApacheCommonsCompress = "1.13"
    val ApacheCommonsIO = "2.4"
    val AsyncAwait = "0.9.6"
    val Jersey = "1.18.6"
    val JettyServlets = "9.3.6.v20151106"
    val JodaTime = "2.9.9"
    val JodaConvert = "1.8.1"
    val UUIDGenerator = "3.1.4"
    val JGraphT = "0.9.3"
    val Diffson = "2.0.2"
    val PlayJson = "2.5.14"
    val JsonSchemaValidator = "2.2.6"
    val RxScala = "0.26.5"
    val MarathonUI = "1.2.0"
    val MarathonApiConsole = "3.0.8-accept"
    val Logback = "1.1.3"
    val Logstash = "4.9"
    val WixAccord = "0.5"
    val Curator = "2.11.1"
    val Java8Compat = "0.8.0"
    val ScalaLogging = "3.5.0"
    val Raven = "7.8.6"

    // test deps versions
    val Mockito = "1.10.19"
    val ScalaTest = "3.0.3"
    val JUnit = "4.12"
    val JUnitBenchmarks = "0.7.2"
    val JMH = "1.19"
    val ScalaCheck = "1.13.5"
  }

  val excludeMortbayJetty = ExclusionRule(organization = "org.mortbay.jetty")
  val excludeJavaxServlet = ExclusionRule(organization = "javax.servlet")

  val aws = "com.amazonaws" % "aws-java-sdk-core" % V.Aws
  val alpakkaS3 = "com.lightbend.akka" %% "akka-stream-alpakka-s3" % V.Alpakka
  val akkaActor = "com.typesafe.akka" %% "akka-actor" % V.Akka
  val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % V.Akka
  val akkaStream = "com.typesafe.akka" %% "akka-stream" % V.Akka
  val akkaHttp = "com.typesafe.akka" %% "akka-http" % V.AkkaHttp
  val akkaHttpPlayJson = "de.heikoseeberger" %% "akka-http-play-json" % "1.10.1"
  val asyncAwait = "org.scala-lang.modules" %% "scala-async" % V.AsyncAwait
  val playJson = "com.typesafe.play" %% "play-json" % V.PlayJson
  val chaos = "mesosphere" %% "chaos" % V.Chaos exclude("org.glassfish.web", "javax.el")
  val guava = "com.google.guava" % "guava" % V.Guava
  val mesos = "org.apache.mesos" % "mesos" % V.Mesos
  val jerseyServlet =  "com.sun.jersey" % "jersey-servlet" % V.Jersey
  val jettyEventSource = "org.eclipse.jetty" % "jetty-servlets" % V.JettyServlets
  val jerseyMultiPart =  "com.sun.jersey.contribs" % "jersey-multipart" % V.Jersey
  val jodaTime = "joda-time" % "joda-time" % V.JodaTime
  val jodaConvert = "org.joda" % "joda-convert" % V.JodaConvert
  val uuidGenerator = "com.fasterxml.uuid" % "java-uuid-generator" % V.UUIDGenerator
  val jGraphT = "org.javabits.jgrapht" % "jgrapht-core" % V.JGraphT
  val beanUtils = "commons-beanutils" % "commons-beanutils" % "1.9.3"
  val jsonSchemaValidator = "com.github.fge" % "json-schema-validator" % V.JsonSchemaValidator
  val rxScala = "io.reactivex" %% "rxscala" % V.RxScala
  val marathonUI = "mesosphere.marathon" % "ui" % V.MarathonUI
  val marathonApiConsole = "mesosphere.marathon" % "api-console" % V.MarathonApiConsole
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % V.Logstash
  val wixAccord = "com.wix" %% "accord-core" % V.WixAccord
  val curator = "org.apache.curator" % "curator-recipes" % V.Curator
  val curatorClient = "org.apache.curator" % "curator-client" % V.Curator
  val curatorFramework = "org.apache.curator" % "curator-framework" % V.Curator
  val java8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % V.Java8Compat
  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % V.ScalaLogging
  val scalaxml = "org.scala-lang.modules" %% "scala-xml" % "1.0.5"
  val raven = "com.getsentry.raven" % "raven-logback" % V.Raven
  val commonsCompress = "org.apache.commons" % "commons-compress" % V.ApacheCommonsCompress
  val akkaSse = "de.heikoseeberger" %% "akka-sse" % V.AkkaSSE

  object Kamon {
    val Version = "0.6.5"

    val core = "io.kamon" %% "kamon-core" % Version % "compile"
    val akka = "io.kamon" %% "kamon-akka" % "0.6.3" % "compile"
    val autoweave = "io.kamon" %% "kamon-autoweave" % Version % "compile"
    val scala = "io.kamon" %% "kamon-scala" % Version % "compile"
    val systemMetrics = "io.kamon" %% "kamon-system-metrics" % Version % "compile"
    val akkaHttp = "io.kamon" %% "kamon-akka-http-experimental" % "0.6.3" % "compile"

    object Backends {
      val statsd = "io.kamon" %% "kamon-statsd" % Version % "compile"
      val datadog = "io.kamon" %% "kamon-datadog" % Version % "compile"
      val jmx = "io.kamon" %% "kamon-jmx" % Version % "compile"
    }

    // there are some issues with the Akka modules that are really unclear
    val all = Seq(core, /*akka,*/ autoweave, systemMetrics, /*akkaHttp,*/ scala,  Backends.statsd, Backends.datadog, Backends.jmx)
  }

  object Test {
    val jmh = "org.openjdk.jmh" % "jmh-generator-annprocess" % V.JMH
    val scalatest = "org.scalatest" %% "scalatest" % V.ScalaTest
    val mockito = "org.mockito" % "mockito-all" % V.Mockito
    val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % V.Akka
    val diffson = "org.gnieh" %% "diffson" % V.Diffson
    val junit = "junit" % "junit" % V.JUnit
    val scalacheck = "org.scalacheck" %% "scalacheck" % V.ScalaCheck
    val wixAccordScalatest = "com.wix" %% "accord-scalatest" % V.WixAccord
    val curatorTest = "org.apache.curator" % "curator-test" % V.Curator
    val commonsIO = "commons-io" % "commons-io" % V.ApacheCommonsIO
  }
}
