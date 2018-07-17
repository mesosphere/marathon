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
  (coverageDir in Test) := target.value / "test-coverage",
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
  (coverageDir in Test) := target.value / "test-coverage",
  (coverageMinimum in Test) := 58,

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
  scalaVersion := "2.12.4",
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

  (scapegoatVersion in ThisBuild) := "1.3.0",

  coverageMinimum := 70,
  coverageFailOnMinimum := true,

  fork in run := true
)

val aspect4jSettings = SbtAspectj.aspectjSettings ++ Seq(
  // required for AJC compile time weaving
  javacOptions in Compile += "-g",
  javaOptions in run ++= (aspectjWeaverOptions in Aspectj).value,
  javaOptions in Test ++= (aspectjWeaverOptions in Aspectj).value,
  aspectjVersion in Aspectj := "1.8.13",
  aspectjInputs in Aspectj += (aspectjCompiledClasses in Aspectj).value,
  products in Compile := (products in Aspectj).value,
  products in Runtime := (products in Aspectj).value,
  products in Compile := (products in Aspectj).value,
  aspectjShowWeaveInfo := true,
  aspectjVerbose := true
)

lazy val packageDebianForLoader = taskKey[File]("Create debian package for active serverLoader")
lazy val packageRpmForLoader = taskKey[File]("Create rpm package for active serverLoader")

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
  debianChangelog in Debian := Some(baseDirectory.value / "changelog.md"),

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
  daemonUser in Docker := "root",
  version in Docker := {
    import sys.process._
    ("./version docker" !!).trim
  },
  (defaultLinuxInstallLocation in Docker) := "/marathon",
  dockerCommands := {
    // kind of a work-around; we want our mesos install and jdk install to come earlier so that Docker can cache them
    val (prefixCommands, restCommands) = dockerCommands.value.splitAt(2)

    // Notes on the script below:
    //
    // 1) The `stretch-slim` does not contain `gnupg` and therefore `apt-key adv` will fail unless it's installed first
    // 2) We are creating a dummy `systemctl` binary in order to satisfy mesos post-install script that tries to invoke
    //   it for registering the systemd task.
    //
    prefixCommands ++
      Seq(Cmd("RUN",
        s"""apt-get update && apt-get install -my wget gnupg && \\
          |apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF && \\
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
          |apt-get install --no-install-recommends -y mesos=${Dependency.V.MesosDebian} && \\
          |rm /usr/bin/systemctl && \\
          |apt-get clean""".stripMargin)) ++
      restCommands ++
      Seq(
        Cmd("ENV", "JAVA_HOME /docker-java-home"),
        Cmd("RUN", "ln -sf /marathon/bin/marathon /marathon/bin/start"))
  },

  /* Linux packaging settings (http://sbt-native-packager.readthedocs.io/en/latest/formats/linux.html)
   *
   * It is expected that these task (packageDebianForLoader, packageRpmForLoader) will be called with various loader
   * configuration specified (systemv, systemd, and upstart as appropriate)
   *
   * See the command alias packageLinux for the invocation */
  packageSummary := "Scheduler for Apache Mesos",
  packageDescription := "Cluster-wide init and control system for services running on\\\n\tApache Mesos",
  maintainer := "Mesosphere Package Builder <support@mesosphere.io>",
  serverLoading := None, // We override this to build for each supported system loader in the packageLinux alias
  debianPackageDependencies in Debian := Seq("java8-runtime-headless", "lsb-release", "unzip", s"mesos (>= ${Dependency.V.MesosDebian})"),
  rpmRequirements in Rpm := Seq("coreutils", "unzip", "java >= 1:1.8.0"),
  rpmVendor := "mesosphere",
  rpmLicense := Some("Apache 2"),
  daemonStdoutLogFile := Some("marathon"),
  version in Rpm := {
    import sys.process._
    val shortCommit = ("./version commit" !!).trim
    s"${version.value}.$shortCommit"
  },
  rpmRelease in Rpm := "1",

  packageDebianForLoader := {
    val debianFile = (packageBin in Debian).value
    val serverLoadingName = (serverLoading in Debian).value.get
    val output = target.value / "packages" / s"${serverLoadingName}-${debianFile.getName}"
    IO.move(debianFile, output)
    streams.value.log.info(s"Moved debian ${serverLoadingName} package $debianFile to $output")
    output
  },
  packageRpmForLoader := {
    val rpmFile = (packageBin in Rpm).value
    val serverLoadingName = (serverLoading in Rpm).value.get
    val output = target.value / "packages" /  s"${serverLoadingName}-${rpmFile.getName}"
    IO.move(rpmFile, output)
    streams.value.log.info(s"Moving rpm ${serverLoadingName} package $rpmFile to $output")
    output
  })

/* Builds all the different package configurations by modifying the session config and running the packaging tasks Note
 *  you cannot build RPM packages unless if you have a functioning `rpmbuild` command (see the alien package for
 *  debian). */
addCommandAlias("packageLinux",
  ";session clear-all" +
  ";set SystemloaderPlugin.projectSettings ++ SystemdPlugin.projectSettings" +
  ";packageDebianForLoader" +
  ";packageRpmForLoader" +

  ";session clear-all" +
  ";set SystemloaderPlugin.projectSettings ++ SystemVPlugin.projectSettings ++ NativePackagerSettings.debianSystemVSettings" +
  ";packageDebianForLoader" +
  ";packageRpmForLoader" +

  ";session clear-all" +
  ";set SystemloaderPlugin.projectSettings ++ UpstartPlugin.projectSettings  ++ NativePackagerSettings.ubuntuUpstartSettings" +
  ";packageDebianForLoader"
)

lazy val `plugin-interface` = (project in file("plugin-interface"))
    .enablePlugins(GitBranchPrompt, BasicLintingPlugin, TestWithCoveragePlugin)
    .settings(testSettings : _*)
    .settings(commonSettings : _*)
    .settings(aspect4jSettings : _*)
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
  .enablePlugins(GitBranchPrompt, JavaServerAppPackaging, DockerPlugin, DebianPlugin, RpmPlugin, JDebPackaging,
    RamlGeneratorPlugin, BasicLintingPlugin, GitVersioning, TestWithCoveragePlugin)
  .dependsOn(`plugin-interface`)
  .settings(testSettings : _*)
  .settings(commonSettings: _*)
  .settings(aspect4jSettings : _*)
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
    scapegoatIgnoredFiles ++= Seq(s"${sourceManaged.value.getPath}/.*"),
    mainClass in Compile := Some("mesosphere.marathon.Main"),
    packageOptions in (Compile, packageBin) ++= Seq(
      Package.ManifestAttributes("Implementation-Version" -> version.value ),
      Package.ManifestAttributes("Scala-Version" -> scalaVersion.value ),
      Package.ManifestAttributes("Git-Commit" -> git.gitHeadCommit.value.getOrElse("unknown") )
    )
  )

lazy val integration = (project in file("./tests/integration"))
  .enablePlugins(GitBranchPrompt, BasicLintingPlugin, TestWithCoveragePlugin)
  .settings(integrationTestSettings : _*)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .settings(
    cleanFiles += baseDirectory { base => base / "sandboxes" }.value
  )
  .dependsOn(marathon % "test->test")

lazy val `mesos-simulation` = (project in file("mesos-simulation"))
  .enablePlugins(GitBranchPrompt, BasicLintingPlugin, TestWithCoveragePlugin)
  .settings(testSettings : _*)
  .settings(aspect4jSettings : _*)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    name := "mesos-simulation"
  )

// see also, benchmark/README.md
lazy val benchmark = (project in file("benchmark"))
  .enablePlugins(JmhPlugin, GitBranchPrompt, BasicLintingPlugin, TestWithCoveragePlugin)
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
  .enablePlugins(GitBranchPrompt, BasicLintingPlugin, TestWithCoveragePlugin)
  .settings(testSettings : _*)
  .settings(aspect4jSettings : _*)
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
