resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots"),
  Classpaths.sbtPluginReleases,
  "Era7 maven releases" at "http://releases.era7.com.s3.amazonaws.com"
)

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.2")
addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.2")
addSbtPlugin("ohnosequences" % "sbt-s3-resolver" % "0.16.0")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")
addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.1.0")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.3.3")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "1.1.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.2.2")
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.0.4")
addSbtPlugin("de.johoop" % "cpd4sbt" % "1.2.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.27")
addSbtPlugin("com.lightbend.sbt" % "sbt-aspectj" % "0.11.0")
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-M15")

libraryDependencies ++= Seq(
  /* 1.0.4 and later versions cause the raml generator to fail; since we are likely moving to the new dcos type
   * generator, we leave this behind. */
  "org.raml" % "raml-parser-2" % "1.0.3",
  "com.eed3si9n" %% "treehugger" % "0.4.3",
  "org.slf4j" % "slf4j-nop" % "1.7.25",
  "org.vafer" % "jdeb" % "1.5" artifacts Artifact("jdeb", "jar", "jar"),
  "com.typesafe.play" %% "play-json" % "2.6.7"
)

sbtPlugin := true

