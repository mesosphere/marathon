resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots"),
  Classpaths.sbtPluginReleases,
  Resolver.jcenterRepo // needed for sbt-s3-resolver
)

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.2")
addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")
addSbtPlugin("ohnosequences" % "sbt-s3-resolver" % "0.19.0")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")
addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.2.2")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.3.3")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "2.0.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.2.2")
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.0.7")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.27")
addSbtPlugin("com.lightbend.sbt" % "sbt-aspectj" % "0.11.0")
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC13")

val JacksonVersion = "2.8.9"

// Plugin dependency hell got you down?
// Run sbt inside of `./project` and inspect dependencies using the coursierDependencyInverseTree command
libraryDependencies ++= Seq(
  /* 1.0.4 and later versions cause the raml generator to fail; since we are likely moving to the new dcos type
   * generator, we leave this behind. */
  "org.raml" % "raml-parser-2" % "1.0.3",
  "com.eed3si9n" %% "treehugger" % "0.4.3",
  "org.slf4j" % "slf4j-nop" % "1.7.25",
  "org.vafer" % "jdeb" % "1.5" artifacts Artifact("jdeb", "jar", "jar")
)

sbtPlugin := true

// Needed for sbt-in-sbt.
scalaVersion := {
  sbtBinaryVersion.value match {
    case "1.0" => "2.12.4"
  }
}

// Needed for jdeb dependency of sbt-native-packager
classpathTypes += "maven-plugin"
