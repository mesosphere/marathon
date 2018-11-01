import sbt.{ExclusionRule, _}

object Dependencies {
  import Dependency._

  val pluginInterface = Seq(
    /* Marathon plugin-interface doesn't directly use these, but we want to expose them as transitive dependencies so
     * that plugins can use it */
    akkaActor % "compile",
    akkaStream % "compile",
    akkaHttp % "compile",
    playJson % "compile",
    scalaLogging % "compile",
    logback % "compile",

    /** Marathon plugin-interface directly uses these via the interfaces exposed to plugins */
    mesos % "compile",
    guava % "compile",
    wixAccord % "compile"
  )

  val mesosClient = Seq(
    playJson % "compile",
    scalaLogging % "compile",
    akkaStream % "compile",
    akkaHttp % "compile"
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
    mesos % "compile",
    jerseyServlet % "compile",
    jerseyMultiPart % "compile",
    jettyEventSource % "compile",
    uuidGenerator % "compile",
    jGraphT % "compile",
    beanUtils % "compile",
    playJson % "compile",
    jsonSchemaValidator % "compile",
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
    alpakkaCodes % "compile",

    // old Chaos deps
    guava % "compile",
    guice % "compile",
    jettyServer % "compile",
    jettyServlet % "compile",
    jettySecurity % "compile",
    jerseyCore % "compile",
    jerseyServer % "compile",
    jerseyServlet % "compile",
    jacksonScala % "compile",
    jacksonJaxrs % "compile",

    julToSlf4j % "compile",

    jerseyHk2 % "compile",

    scallop % "compile",

    // test
    Test.diffson % "test",
    Test.scalatest % "test",
    Test.mockito % "test",
    Test.akkaTestKit % "test",
    Test.akkaHttpTestKit % "test",
    Test.junit % "test",
    Test.scalacheck % "test"
  ) ++ Curator.all ++ DropwizardMetrics.all).map(
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
    val Akka = "2.5.14"
    val AkkaHttp = "10.0.11"
    val Alpakka  = "0.14"
    val ApacheCommonsCompress = "1.13"
    val ApacheCommonsIO = "2.6"
    val AsyncAwait = "0.9.7"
    val Aws = "1.11.243"
    val Diffson = "2.2.2"
    val Guava = "20.0"
    val Guice = "4.1.0"
    val JGraphT = "0.9.3"
    val Jackson = "2.9.5"
    val Java8Compat = "0.9.0"
    val Jersey = "2.27"
    val Jetty = "9.4.8.v20171121"
    val JettyServlets = "9.4.8.v20171121"
    val JsonSchemaValidator = "2.2.6"
    val Logback = "1.2.3"
    val Logstash = "4.9"
    val MarathonApiConsole = "3.0.8-accept"
    val MarathonUI = "1.3.1"
    val Mesos = "1.5.0"
    val Mustache = "0.9.0"
    val PlayJson = "2.6.7"
    val Raven = "8.0.3"
    val ScalaLogging = "3.7.2"
    val ScalaPb = "0.6.6"
    val Scallop = "3.1.2"
    val ServletApi = "2.5"
    val Slf4j = "1.7.21"
    val UUIDGenerator = "3.1.4"
    val WixAccord = "0.7.1"

    // Version of Mesos to use in Dockerfile.
    val MesosDebian = "1.5.1-2.0.1"

    // test deps versions
    val JMH = "1.19"
    val JUnit = "4.12"
    val JUnitBenchmarks = "0.7.2"
    val Mockito = "1.10.19"
    val ScalaCheck = "1.13.5"
    val ScalaTest = "3.0.4"
  }

  val excludeMortbayJetty = ExclusionRule(organization = "org.mortbay.jetty")
  val excludeJavaxServlet = ExclusionRule(organization = "javax.servlet")

  val akkaActor = "com.typesafe.akka" %% "akka-actor" % V.Akka
  val akkaHttp = "com.typesafe.akka" %% "akka-http" % V.AkkaHttp
  val akkaHttpPlayJson = "de.heikoseeberger" %% "akka-http-play-json" % "1.18.1"
  val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % V.Akka
  val akkaStream = "com.typesafe.akka" %% "akka-stream" % V.Akka
  val alpakkaCodes = "com.lightbend.akka" %% "akka-stream-alpakka-simple-codecs" % V.Alpakka
  val alpakkaS3 = "com.lightbend.akka" %% "akka-stream-alpakka-s3" % V.Alpakka
  val asyncAwait = "org.scala-lang.modules" %% "scala-async" % V.AsyncAwait
  val aws = "com.amazonaws" % "aws-java-sdk-core" % V.Aws
  val beanUtils = "commons-beanutils" % "commons-beanutils" % "1.9.3"
  val commonsCompress = "org.apache.commons" % "commons-compress" % V.ApacheCommonsCompress
  val commonsIO = "commons-io" % "commons-io" % V.ApacheCommonsIO
  val guava = "com.google.guava" % "guava" % V.Guava
  val guice = "com.google.inject" % "guice" % V.Guice
  val jGraphT = "org.javabits.jgrapht" % "jgrapht-core" % V.JGraphT
  val jacksonJaxrs = "com.fasterxml.jackson.jaxrs" % "jackson-jaxrs-json-provider" % V.Jackson
  val jacksonScala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % V.Jackson
  val java8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % V.Java8Compat

  val jerseyCore = "org.glassfish.jersey.core" % "jersey-common" % V.Jersey
  val jerseyMultiPart =  "org.glassfish.jersey.media" % "jersey-media-multipart" % V.Jersey
  val jerseyServer = "org.glassfish.jersey.core" % "jersey-server" % V.Jersey
  val jerseyServlet =  "org.glassfish.jersey.containers" % "jersey-container-servlet" % V.Jersey

  // Jersey 2 still relies on hk2. See https://jersey.github.io/release-notes/2.26.html
  val jerseyHk2 =  "org.glassfish.jersey.inject" % "jersey-hk2" % V.Jersey

  val jettyEventSource = "org.eclipse.jetty" % "jetty-servlets" % V.JettyServlets
  val jettySecurity = "org.eclipse.jetty" % "jetty-security" % V.Jetty
  val jettyServer = "org.eclipse.jetty" % "jetty-server" % V.Jetty
  val jettyServlet = "org.eclipse.jetty" % "jetty-servlet" % V.Jetty
  val jsonSchemaValidator = "com.github.fge" % "json-schema-validator" % V.JsonSchemaValidator
  val julToSlf4j = "org.slf4j" % "jul-to-slf4j" % V.Slf4j
  val logback = "ch.qos.logback" % "logback-classic" % V.Logback
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % V.Logstash
  val marathonApiConsole = "mesosphere.marathon" % "api-console" % V.MarathonApiConsole
  val marathonUI = "mesosphere.marathon" % "ui" % V.MarathonUI
  val mesos = "org.apache.mesos" % "mesos" % V.Mesos
  val playJson = "com.typesafe.play" %% "play-json" % V.PlayJson
  val raven = "com.getsentry.raven" % "raven-logback" % V.Raven
  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % V.ScalaLogging
  val scalaPb = "com.trueaccord.scalapb" %% "compilerplugin" % V.ScalaPb
  val scallop = "org.rogach" %% "scallop" % V.Scallop
  val servletApi = "javax.servlet" % "servlet-api" % V.ServletApi
  val uuidGenerator = "com.fasterxml.uuid" % "java-uuid-generator" % V.UUIDGenerator
  val wixAccord = "com.wix" %% "accord-core" % V.WixAccord

  object Curator {
    /**
      * According to Curator's Zookeeper Compatibility Docs [http://curator.apache.org/zk-compatibility.html], 4.0.0
      * is the recommended version to use with Zookeeper 3.4.x. You do need to exclude the 3.5.x dependency and specify
      * your 3.4.x dependency.
      */
    val Version = "4.0.1"

    val TestVersion = "2.12.0"

    val excludeZk35 = ExclusionRule(organization = "org.apache.zookeeper", name = "zookeeper")

    val curator = Seq(
      "org.apache.curator" % "curator-recipes" % Version % "compile",
      "org.apache.curator" % "curator-client" % Version % "compile",
      "org.apache.curator" % "curator-framework" % Version % "compile",
      "org.apache.curator" % "curator-x-async" % Version % "compile",
      "org.apache.curator" % "curator-test" % TestVersion % "test").map(_.excludeAll(excludeZk35))

    val zk = Seq("org.apache.zookeeper" % "zookeeper" % "3.4.11")
    val all = curator ++ zk
  }

  object DropwizardMetrics {
    val Version = "4.0.2"

    val core = "io.dropwizard.metrics" % "metrics-core" % Version % "compile"
    val jersey = "io.dropwizard.metrics" % "metrics-jersey2" % Version % "compile"
    val jetty = "io.dropwizard.metrics" % "metrics-jetty9" % Version % "compile"
    val jvm = "io.dropwizard.metrics" % "metrics-jvm" % Version % "compile"
    val servlets = "io.dropwizard.metrics" % "metrics-servlets" % Version % "compile"
    val rollingMetrics = "com.github.vladimir-bukhtoyarov" % "rolling-metrics" % "2.0.4" % "compile"
    val hdrHistogram = "org.hdrhistogram" % "HdrHistogram" % "2.1.10" % "compile"

    val all = Seq(core, jersey, jetty, jvm, servlets, rollingMetrics, hdrHistogram)
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
