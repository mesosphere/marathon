import java.time.{LocalDate, ZoneOffset}
import java.time.format.DateTimeFormatter

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.packager.docker.Cmd
import mesosphere.maven.MavenSettings.{loadM2Credentials, loadM2Resolvers}
import mesosphere.raml.RamlGeneratorPlugin
import NativePackagerHelper.directory

import scalariform.formatter.preferences._

credentials ++= loadM2Credentials(streams.value.log)
resolvers ++= loadM2Resolvers(sLog.value)

resolvers += Resolver.sonatypeRepo("snapshots")

addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % "0.1.17")

val silencerVersion = "1.1"
addCompilerPlugin("com.github.ghik" %% "silencer-plugin" % silencerVersion)
libraryDependencies += "com.github.ghik" %% "silencer-lib" % silencerVersion % Provided

lazy val formatSettings = Seq(
  ScalariformKeys.preferences := FormattingPreferences()
    .setPreference(DanglingCloseParenthesis, Preserve)
    .setPreference(DoubleIndentConstructorArguments, true)
    .setPreference(PlaceScaladocAsterisksBeneathSecondAsterisk, true)
    .setPreference(PreserveSpaceBeforeArguments, true)
    .setPreference(SpacesAroundMultiImports, false)
)

// Pass arguments to Scalatest runner:
// http://www.scalatest.org/user_guide/using_the_runner
lazy val testSettings = Seq(
  parallelExecution in Test := true,
  testForkedParallel in Test := true,
  testListeners := Nil, // TODO(MARATHON-8215): Remove this line
  testOptions in Test := Seq(
    Tests.Argument(
      "-u", "target/test-reports", // TODO(MARATHON-8215): Remove this line
      "-o", "-eDFG",
      "-y", "org.scalatest.WordSpec")),
  fork in Test := true
)

// Pass arguments to Scalatest runner:
// http://www.scalatest.org/user_guide/using_the_runner
lazy val integrationTestSettings = Seq(
  testListeners := Nil, // TODO(MARATHON-8215): Remove this line

  fork in Test := true,
  testOptions in Test := Seq(
    Tests.Argument(
      "-u", "target/test-reports", // TODO(MARATHON-8215): Remove this line
      "-o", "-eDFG",
      "-y", "org.scalatest.WordSpec")),
  parallelExecution in Test := true,
  testForkedParallel in Test := true,
  concurrentRestrictions in Test := Seq(Tags.limitAll(math.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2))),
  javaOptions in (Test, test) ++= Seq(
    "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-min=2",
    "-Dakka.actor.default-dispatcher.fork-join-executor.factor=1",
    "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-max=4",
    "-Dscala.concurrent.context.minThreads=2",
    "-Dscala.concurrent.context.maxThreads=32"
  ),
  concurrentRestrictions in Test := Seq(Tags.limitAll(math.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2)))
)

lazy val commonSettings = Seq(
  autoCompilerPlugins := true,
  organization := "mesosphere.marathon",
  scalaVersion := "2.12.7",
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
    //FIXME: CORE-977 and MESOS-7368 are filed and need to be resolved to re-enable this
    // "-Xfatal-warnings",
    "-Yno-adapted-args",
    "-Ywarn-numeric-widen",
    //"-Ywarn-dead-code", We should turn this one on soon
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    //"-Ywarn-unused", We should turn this one on soon
    "-Ywarn-unused:-locals,imports",
    //"-Ywarn-value-discard", We should turn this one on soon.
  ),
  // Don't need any linting, etc for docs, so gain a small amount of build time there.
  scalacOptions in (Compile, doc) := Seq("-encoding", "UTF-8", "-deprecation", "-feature", "-Xfuture"),
  javacOptions in Compile ++= Seq(
    "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation"
  ),
  resolvers ++= Seq(
    Resolver.JCenterRepository,
    "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/",
    "Apache Shapshots" at "https://repository.apache.org/content/repositories/snapshots/",
    "Mesosphere Public Repo" at "https://downloads.mesosphere.com/maven"
  ),
  cancelable in Global := true,
  publishTo := Some(s3resolver.value(
    "Mesosphere Public Repo (S3)",
    s3("downloads.mesosphere.io/maven")
  )),
  s3credentials := DefaultAWSCredentialsProviderChain.getInstance(),
  s3region :=  com.amazonaws.services.s3.model.Region.US_Standard,

  fork in run := true
)

/**
  * The documentation for sbt-native-package can be foound here:
  * - General, non-vendor specific settings (such as launch script):
  *     http://sbt-native-packager.readthedocs.io/en/latest/archetypes/java_app/index.html#usage
  *
  * - Linux packaging settings
  *     http://sbt-native-packager.readthedocs.io/en/latest/archetypes/java_app/index.html#usage
  */
lazy val packagingSettings = Seq(
  bashScriptExtraDefines += IO.read((baseDirectory.value / "project" / "NativePackagerSettings" / "extra-defines.bash")),
  mappings in (Compile, packageDoc) := Seq(),

  (packageName in Universal) := {
    import sys.process._
    val shortCommit = ("./version commit" !!).trim
    s"${packageName.value}-${version.value}-$shortCommit"
  },

  /* Universal packaging (docs) - http://sbt-native-packager.readthedocs.io/en/latest/formats/universal.html
   */
  universalArchiveOptions in (UniversalDocs, packageZipTarball) := Seq("-pcvf"), // Remove this line once fix for https://github.com/sbt/sbt-native-packager/issues/1019 is released
  (packageName in UniversalDocs) := {
    import sys.process._
    val shortCommit = ("./version commit" !!).trim
    s"${packageName.value}-docs-${version.value}-$shortCommit"
  },
  (topLevelDirectory in UniversalDocs) := { Some((packageName in UniversalDocs).value) },
  mappings in UniversalDocs ++= directory("docs/docs"),


  /* Docker config (http://sbt-native-packager.readthedocs.io/en/latest/formats/docker.html)
   */
  dockerBaseImage := "debian:stretch-slim",
  dockerRepository := Some("mesosphere"),
  daemonUser in Docker := "nobody",
  daemonGroup in Docker := "nogroup",
  version in Docker := {
    import sys.process._
    ("./version docker" !!).trim
  },
  (defaultLinuxInstallLocation in Docker) := "/marathon",
  maintainer := "Mesosphere Package Builder <support@mesosphere.io>",
  dockerCommands := {
    // kind of a work-around; we need our chown /marathon command to come after the WORKDIR command, and installation
    // commands to preceed adding the Marthon artifact so that Docker can cache them
    val (prefixCommands, restCommands) = dockerCommands.value.splitAt(dockerCommands.value.indexWhere(_.makeContent.startsWith("WORKDIR ")) + 1)

    // Notes on the script below:
    //
    // 1) The `stretch-slim` does not contain `gnupg` and therefore `apt-key adv` will fail unless it's installed first
    // 2) We are creating a dummy `systemctl` binary in order to satisfy mesos post-install script that tries to invoke
    //   it for registering the systemd task.
    //
    prefixCommands ++
      Seq(Cmd("RUN",
        s"""apt-get update && apt-get install -my wget gnupg && \\
          |apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv DF7D54CBE56151BF && \\
          |apt-get update -y && \\
          |apt-get upgrade -y && \\
          |echo "deb http://ftp.debian.org/debian stretch-backports main" | tee -a /etc/apt/sources.list && \\
          |echo "deb http://repos.mesosphere.com/debian stretch-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \\
          |echo "deb http://repos.mesosphere.com/debian stretch main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \\
          |apt-get update && \\
          |# jdk setup
          |mkdir -p /usr/share/man/man1 && \\
          |apt-get install -y openjdk-8-jdk-headless openjdk-8-jre-headless ca-certificates-java=20170531+nmu1 && \\
          |/var/lib/dpkg/info/ca-certificates-java.postinst configure && \\
          |ln -svT "/usr/lib/jvm/java-8-openjdk-$$(dpkg --print-architecture)" /docker-java-home && \\
          |# mesos setup
          |echo exit 0 > /usr/bin/systemctl && chmod +x /usr/bin/systemctl && \\
          |# Workaround required due to https://github.com/mesosphere/mesos-deb-packaging/issues/102
          |# Remove after upgrading to Mesos 1.7.0
          |apt-get install -y libcurl3-nss && \\
          |apt-get install --no-install-recommends -y mesos=${Dependency.V.MesosDebian} && \\
          |rm /usr/bin/systemctl && \\
          |apt-get clean && \\
          |chown nobody:nogroup /marathon""".stripMargin)) ++
      restCommands ++
      Seq(
        Cmd("ENV", "JAVA_HOME /docker-java-home"),
        Cmd("RUN", s"""ln -sf /marathon/bin/marathon /marathon/bin/start""".stripMargin))
  })

lazy val `plugin-interface` = (project in file("plugin-interface"))
    .enablePlugins(GitBranchPrompt, BasicLintingPlugin)
    .settings(testSettings : _*)
    .settings(commonSettings : _*)
    .settings(formatSettings : _*)
    .settings(
      version := {
        import sys.process._
        ("./version" !!).trim
      },
      name := "plugin-interface",
      libraryDependencies ++= Dependencies.pluginInterface
    )

lazy val marathon = (project in file("."))
  .enablePlugins(GitBranchPrompt, JavaServerAppPackaging, DockerPlugin,
    RamlGeneratorPlugin, BasicLintingPlugin, GitVersioning)
  .dependsOn(`plugin-interface`)
  .settings(testSettings : _*)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .settings(packagingSettings: _*)
  .settings(
    version := {
      import sys.process._
      ("./version" !!).trim
    },
    unmanagedResourceDirectories in Compile += file("docs/docs/rest-api"),
    libraryDependencies ++= Dependencies.marathon,
    sourceGenerators in Compile += (ramlGenerate in Compile).taskValue,
    mainClass in Compile := Some("mesosphere.marathon.Main"),
    packageOptions in (Compile, packageBin) ++= Seq(
      Package.ManifestAttributes("Implementation-Version" -> version.value ),
      Package.ManifestAttributes("Scala-Version" -> scalaVersion.value ),
      Package.ManifestAttributes("Git-Commit" -> git.gitHeadCommit.value.getOrElse("unknown") )
    )
  )

lazy val ammonite = (project in file("./tools/repl-server"))
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .settings(
    mainClass in Compile := Some("ammoniterepl.Main"),
    libraryDependencies += "com.lihaoyi" % "ammonite-sshd" % "1.5.0" cross CrossVersion.full
  )
  .dependsOn(marathon)

lazy val integration = (project in file("./tests/integration"))
  .enablePlugins(GitBranchPrompt, BasicLintingPlugin)
  .settings(integrationTestSettings : _*)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .settings(
    cleanFiles += baseDirectory { base => base / "sandboxes" }.value
  )
  .dependsOn(marathon % "test->test")

lazy val `mesos-simulation` = (project in file("mesos-simulation"))
  .enablePlugins(GitBranchPrompt, BasicLintingPlugin)
  .settings(testSettings : _*)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    name := "mesos-simulation"
  )

// see also, benchmark/README.md
lazy val benchmark = (project in file("benchmark"))
  .enablePlugins(JmhPlugin, GitBranchPrompt, BasicLintingPlugin)
  .settings(testSettings : _*)
  .settings(commonSettings : _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    testOptions in Test += Tests.Argument(TestFrameworks.JUnit),
    libraryDependencies ++= Dependencies.benchmark
  )

// see also mesos-client/README.md
lazy val `mesos-client` = (project in file("mesos-client"))
  .enablePlugins(GitBranchPrompt, BasicLintingPlugin)
  .settings(testSettings : _*)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    name := "mesos-client",
    libraryDependencies ++= Dependencies.mesosClient,

    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value
    )
  )
