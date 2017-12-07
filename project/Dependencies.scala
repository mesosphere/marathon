import sbt.{ExclusionRule, _}

object Dependencies {
  import Dependency._

  val pluginInterface = Seq(
    akkaActor % "compile",
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
    java8Compat % "compile",
    scalaLogging % "compile",
    logback % "compile",
    logstash % "compile",
    raven % "compile",
    akkaHttpPlayJson % "compile",
    alpakkaS3 % "compile",
    commonsCompress % "compile", // used for tar flow
    commonsIO % "compile",

    // test
    Test.diffson % "test",
    Test.scalatest % "test",
    Test.mockito % "test",
    Test.akkaTestKit % "test",
    Test.akkaHttpTestKit % "test",
    Test.junit % "test",
    Test.scalacheck % "test"
  ) ++ Curator.all ++ Kamon.all).map(
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
    val Aws = "1.11.243"
    val Alpakka  = "0.14"
    val Chaos = "0.10.0"
    val Guava = "19.0"
    val Mesos = "1.5.0-health-check-ipv6"
    // Version of Mesos to use in Dockerfile.
    val MesosDebian = "1.4.0-2.0.1"
    val OpenJDK = "openjdk:8u121-jdk"
    val Akka = "2.5.7"
    val AkkaHttp = "10.0.11"
    val ApacheCommonsCompress = "1.13"
    val ApacheCommonsIO = "2.6"
    val AsyncAwait = "0.9.7"
    val Jersey = "1.18.6"
    val JettyServlets = "9.3.6.v20151106"
    val UUIDGenerator = "3.1.4"
    val JGraphT = "0.9.3"
    val Diffson = "2.2.2"
    val PlayJson = "2.6.7"
    val JsonSchemaValidator = "2.2.6"
    val RxScala = "0.26.5"
    val MarathonUI = "1.3.0"
    val MarathonApiConsole = "3.0.8-accept"
    val Logback = "1.2.3"
    val Logstash = "4.9"
    val WixAccord = "0.7.1"
    val Java8Compat = "0.8.0"
    val ScalaLogging = "3.7.2"
    val Raven = "8.0.3"
    val JacksonVersion = "2.8.9"

    // test deps versions
    val Mockito = "1.10.19"
    val ScalaTest = "3.0.4"
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
  val akkaHttpPlayJson = "de.heikoseeberger" %% "akka-http-play-json" % "1.18.1"
  val asyncAwait = "org.scala-lang.modules" %% "scala-async" % V.AsyncAwait
  val playJson = "com.typesafe.play" %% "play-json" % V.PlayJson
  val chaos = "mesosphere" %% "chaos" % V.Chaos exclude("org.glassfish.web", "javax.el")
  val guava = "com.google.guava" % "guava" % V.Guava
  val mesos = "org.apache.mesos" % "mesos" % V.Mesos
  val jerseyServlet =  "com.sun.jersey" % "jersey-servlet" % V.Jersey
  val jettyEventSource = "org.eclipse.jetty" % "jetty-servlets" % V.JettyServlets
  val jerseyMultiPart =  "com.sun.jersey.contribs" % "jersey-multipart" % V.Jersey
  val uuidGenerator = "com.fasterxml.uuid" % "java-uuid-generator" % V.UUIDGenerator
  val jGraphT = "org.javabits.jgrapht" % "jgrapht-core" % V.JGraphT
  val beanUtils = "commons-beanutils" % "commons-beanutils" % "1.9.3"
  val jsonSchemaValidator = "com.github.fge" % "json-schema-validator" % V.JsonSchemaValidator
  val rxScala = "io.reactivex" %% "rxscala" % V.RxScala
  val marathonUI = "mesosphere.marathon" % "ui" % V.MarathonUI
  val marathonApiConsole = "mesosphere.marathon" % "api-console" % V.MarathonApiConsole
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % V.Logstash
  val wixAccord = "com.wix" %% "accord-core" % V.WixAccord
  val java8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % V.Java8Compat
  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % V.ScalaLogging
  val logback = "ch.qos.logback" % "logback-classic" % V.Logback
  val scalaxml = "org.scala-lang.modules" %% "scala-xml" % "1.0.5"
  val raven = "com.getsentry.raven" % "raven-logback" % V.Raven
  val commonsCompress = "org.apache.commons" % "commons-compress" % V.ApacheCommonsCompress
  val commonsIO = "commons-io" % "commons-io" % V.ApacheCommonsIO

  object Curator {
    /**
      * According to Curator's Zookeeper Compatibility Docs [http://curator.apache.org/zk-compatibility.html], 4.0.0
      * is the recommended version to use with Zookeeper 3.4.x. You do need to exclude the 3.5.x dependency and specify
      * your 3.4.x dependency.
      */
    val Version = "4.0.0"

    /**
      * curator-test 4.0.0 causes scary error message for aspectj weaver:
      *
      *   java.lang.IllegalStateException: Expecting .,<, or ;, but found curatortest while unpacking (some shaded jar)
      *
      * Also, it launches Zookeeper 3.5.3
      */
    val TestVersion = "2.11.1" // CuratorTest 4.0.0 causes scary error message for aspectj weaver, and seems to target

    val excludeZk35 = ExclusionRule(organization = "org.apache.zookeeper", name = "zookeeper")

    val curator = Seq(
      "org.apache.curator" % "curator-recipes" % Version % "compile",
      "org.apache.curator" % "curator-client" % Version % "compile",
      "org.apache.curator" % "curator-framework" % Version % "compile",
      "org.apache.curator" % "curator-test" % TestVersion % "test").map(_.excludeAll(excludeZk35))

    val zk = Seq("org.apache.zookeeper" % "zookeeper" % "3.4.11")
    val all = curator ++ zk
  }

  object Kamon {
    val Version = "0.6.7"

    // Note - While Kamon depends on Akka 2.4.x, version 0.6.7 is compatible with Akka 2.5.x
    val core = "io.kamon" %% "kamon-core" % Version % "compile"
    val akka = "io.kamon" %% "kamon-akka-2.5" % Version % "compile"
    val autoweave = "io.kamon" %% "kamon-autoweave" % "0.6.5" % "compile"
    val scala = "io.kamon" %% "kamon-scala" % Version % "compile"
    val systemMetrics = "io.kamon" %% "kamon-system-metrics" % Version % "compile"

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
    val akkaHttpTestKit = "com.typesafe.akka" %% "akka-http-testkit" % V.AkkaHttp
    val diffson = "org.gnieh" %% "diffson-play-json" % V.Diffson
    val junit = "junit" % "junit" % V.JUnit
    val scalacheck = "org.scalacheck" %% "scalacheck" % V.ScalaCheck
  }
}
