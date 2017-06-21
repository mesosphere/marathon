resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots"),
  Classpaths.sbtPluginReleases,
  "Era7 maven releases" at "http://releases.era7.com.s3.amazonaws.com"
)

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.6.0")
addSbtPlugin("io.spray" % "sbt-revolver" % "0.8.0")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.2")
addSbtPlugin("ohnosequences" % "sbt-s3-resolver" % "0.15.0")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.0")
addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.1.0")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.2.0")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "1.0.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.8.5")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.5")
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.0.4")
addSbtPlugin("de.johoop" % "cpd4sbt" % "1.2.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.25")
addSbtPlugin("com.typesafe.sbt" % "sbt-aspectj" % "0.10.6")
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-M15")

libraryDependencies ++= Seq(
  "org.raml" % "raml-parser-2" % "1.0.0",
  "com.eed3si9n" %% "treehugger" % "0.4.1",
  "org.slf4j" % "slf4j-nop" % "1.7.22",
  "org.vafer" % "jdeb" % "1.3" artifacts Artifact("jdeb", "jar", "jar"),
  "com.typesafe.play" %% "play-json" % "2.4.11"
)

sbtPlugin := true

