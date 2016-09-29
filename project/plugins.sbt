resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots"),
  Classpaths.sbtPluginReleases,
  "Era7 maven releases" at "http://releases.era7.com.s3.amazonaws.com"
)

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.3")
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.3")
addSbtPlugin("org.scalariform" % "sbt-scalariform" % "latest.release")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.6.1")
addSbtPlugin("io.spray" % "sbt-revolver" % "0.8.0")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "latest.release")
addSbtPlugin("ohnosequences" % "sbt-s3-resolver" % "0.15.0")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "latest.release")
addSbtPlugin("org.scoverage" % "sbt-coveralls" % "latest.release")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.2.0")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "latest.release")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.8.5")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.1")
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "latest.release")
addSbtPlugin("de.johoop" % "cpd4sbt" % "1.2.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.15")

libraryDependencies ++= Seq(
  "org.raml" % "raml-parser-2" % "1.0.0",
  "com.eed3si9n" %% "treehugger" % "0.4.1",
  "org.slf4j" % "slf4j-nop" % "1.7.21"
)

sbtPlugin := true

